package cumt.zongzuo.community.ai.agent.web;

import cumt.zongzuo.community.ai.agent.turn.AgentTurnRuntimeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class AgentTurnWebConfigurationTest {
    @Test void saturatedMvcStreamReturnsExplicit503WithoutFailingTheGenerationTask() throws Exception {
        var settings = new AgentTurnRuntimeProperties();
        settings.setSseConcurrency(1);
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (var executor = AgentTurnWebConfiguration.agentSseExecutor(settings)) {
            executor.execute(() -> {
                started.countDown();
                try { finish.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            try {
                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
                var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                        .standaloneSetup(new StreamEndpoint())
                        .setControllerAdvice(new cumt.zongzuo.community.ai.web.AiProblemDetailAdvice()).build();
                mvc.getDispatcherServlet().getWebApplicationContext()
                        .getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.class)
                        .setTaskExecutor(executor);
                var request = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/test-stream")).andReturn();
                // 提交拒绝发生在 Callable 开始之前，MockMvc 的 Callable 结果拦截器不会执行。
                // 从真实 WebAsyncManager 检查拒绝结果，再模拟容器的 ASYNC 重分发。
                assertThat(org.springframework.web.context.request.async.WebAsyncUtils
                        .getAsyncManager(request.getRequest()).getConcurrentResult())
                        .isInstanceOf(TaskRejectedException.class);
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/test-stream")
                        .with(ignored -> {
                            request.getRequest().setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
                            return request.getRequest();
                        }))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "1"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                                .value("AGENT_STREAM_CAPACITY_EXHAUSTED"));
            } finally { finish.countDown(); }
        }
    }

    @cumt.zongzuo.community.ai.web.AiApi
    @org.springframework.web.bind.annotation.RestController
    static class StreamEndpoint {
        @org.springframework.web.bind.annotation.GetMapping("/test-stream")
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody stream() {
            return output -> output.write('x');
        }
    }

    @Test void slowStreamsUseVirtualThreadsAndFailFastAtTheirOwnLimit() throws Exception {
        var settings = new AgentTurnRuntimeProperties();
        settings.setSseConcurrency(1);
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicBoolean virtual = new AtomicBoolean();
        try (var executor = AgentTurnWebConfiguration.agentSseExecutor(settings)) {
            executor.execute(() -> {
                virtual.set(Thread.currentThread().isVirtual()); started.countDown();
                try { finish.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            try {
                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(virtual).isTrue();
                assertThatThrownBy(() -> executor.execute(() -> { }))
                        .isInstanceOf(TaskRejectedException.class);
            } finally { finish.countDown(); }
        }
    }

    @Test void servletTimeoutCannotCutOffAnOtherwiseValidBusinessStream() {
        var settings = new AgentTurnRuntimeProperties();
        settings.setSseTimeout(Duration.ofSeconds(40));
        try (var executor = AgentTurnWebConfiguration.agentSseExecutor(settings)) {
            var configurer = new ExposedAsyncSupport();
            new AgentTurnWebConfiguration(settings, executor).configureAsyncSupport(configurer);
            assertThat(configurer.timeout()).isBetween(40_001L, 50_000L);
            assertThat(configurer.executor()).isSameAs(executor);
        }
    }

    private static final class ExposedAsyncSupport extends AsyncSupportConfigurer {
        Long timeout() { return getTimeout(); }
        Object executor() { return getTaskExecutor(); }
    }
}
