package cumt.zongzuo.community.ai.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnCapacity;
import cumt.zongzuo.community.ai.web.AiProblemDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;

/** 在 CORS 之后、JWT 查库之前预留整轮容量，过载不再先创建 turn 再补偿。 */
public final class AgentTurnCapacityFilter extends OncePerRequestFilter {
    private final AgentTurnCapacity capacity;
    private final ObjectMapper mapper;
    public AgentTurnCapacityFilter(AgentTurnCapacity capacity, ObjectMapper mapper) {
        this.capacity = capacity;
        this.mapper = mapper;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        // servletPath 已由容器解码，避免编码后的路径绕过保护；兼容带 context-path 部署。
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        return !"POST".equals(request.getMethod()) || !"/api/agent/turns".equals(path);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        AgentTurnCapacity.Reservation reservation;
        try { reservation = capacity.reserveRequest(); }
        catch (RejectedExecutionException saturated) {
            AiProblemDetails.write(request, response, mapper, HttpStatus.SERVICE_UNAVAILABLE,
                    "AGENT_CAPACITY_EXHAUSTED", true, 1);
            return;
        }
        // 只捕获预留失败，不把下游编程错误误报成容量不足。同步入口返回后必定清理 ThreadLocal。
        try (reservation) { chain.doFilter(request, response); }
    }
}
