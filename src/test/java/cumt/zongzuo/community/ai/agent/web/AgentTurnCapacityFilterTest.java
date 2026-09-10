package cumt.zongzuo.community.ai.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnCapacity;
import cumt.zongzuo.community.ai.agent.turn.CapacityAwareTurnExecutor;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class AgentTurnCapacityFilterTest {
    @Test void saturationRejectsBeforeAuthenticationOrDatabaseAndPreservesGetRecovery() throws Exception {
        var capacity = new AgentTurnCapacity(1);
        var filter = new AgentTurnCapacityFilter(capacity, new ObjectMapper());
        var executor = new CapacityAwareTurnExecutor(Executors.newSingleThreadExecutor(), capacity);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger downstreamCalls = new AtomicInteger();
        try {
            filter.doFilter(request("POST", "/api/agent/turns"), new MockHttpServletResponse(), (req, res) -> {
                downstreamCalls.incrementAndGet();
                executor.execute(() -> {
                    try { finish.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
            });
            var response = new MockHttpServletResponse();
            filter.doFilter(request("POST", "/api/agent/turns"), response,
                    (req, res) -> downstreamCalls.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getContentAsString()).contains("AGENT_CAPACITY_EXHAUSTED");
            assertThat(response.getHeader("Retry-After")).isEqualTo("1");
            assertThat(downstreamCalls).hasValue(1);
            // 满载仍允许查快照、续订事件和取消，不能把恢复通道一起封死。
            filter.doFilter(request("GET", "/api/agent/turns/1/events"), new MockHttpServletResponse(),
                    (req, res) -> downstreamCalls.incrementAndGet());
            filter.doFilter(request("POST", "/api/agent/turns/1/cancel"), new MockHttpServletResponse(),
                    (req, res) -> downstreamCalls.incrementAndGet());
            assertThat(downstreamCalls).hasValue(3);
            finish.countDown();
            await().untilAsserted(() -> { try (var ignored = capacity.reserveRequest()) { } });
        } finally { finish.countDown(); executor.shutdownNow(); }
    }

    @Test void unauthorizedAndExceptionalRequestsDoNotLeakReservations() throws Exception {
        var capacity = new AgentTurnCapacity(1);
        var filter = new AgentTurnCapacityFilter(capacity, new ObjectMapper());
        filter.doFilter(request("POST", "/api/agent/turns"), new MockHttpServletResponse(),
                (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401));
        assertThatThrownBy(() -> filter.doFilter(request("POST", "/api/agent/turns"),
                new MockHttpServletResponse(), (req, res) -> { throw new ServletException("synthetic"); }))
                .isInstanceOf(ServletException.class);
        try (var ignored = capacity.reserveRequest()) { }
    }

    @Test void decodedServletPathIsProtectedWithAContextPath() {
        var filter = new AgentTurnCapacityFilter(new AgentTurnCapacity(1), new ObjectMapper());
        var request = new MockHttpServletRequest("POST", "/community/api/agent/%74urns");
        request.setContextPath("/community");
        request.setServletPath("/api/agent/turns");
        assertThat(filter.shouldNotFilter(request)).isFalse();
        assertThat(filter.shouldNotFilter(request("OPTIONS", "/api/agent/turns"))).isTrue();
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
