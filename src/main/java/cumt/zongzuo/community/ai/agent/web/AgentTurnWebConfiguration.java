package cumt.zongzuo.community.ai.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnCapacity;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnRuntimeProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** SSE 使用独立、有硬并发上限的虚拟线程，不占用整轮生成线程，也不无限创建平台线程。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"metro.ai.enabled", "metro.ai.agent.enabled"}, havingValue = "true")
public class AgentTurnWebConfiguration implements WebMvcConfigurer {
    private final AgentTurnRuntimeProperties settings;
    private final SimpleAsyncTaskExecutor executor;
    public AgentTurnWebConfiguration(AgentTurnRuntimeProperties settings,
                                     @Qualifier("agentSseExecutor") SimpleAsyncTaskExecutor executor) {
        this.settings = settings;
        this.executor = executor;
    }

    @Bean(destroyMethod = "close")
    static SimpleAsyncTaskExecutor agentSseExecutor(AgentTurnRuntimeProperties settings) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("agent-sse-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(settings.getSseConcurrency());
        executor.setRejectTasksWhenLimitReached(true);
        executor.setTaskTerminationTimeout(5000);
        executor.setCancelRemainingTasksOnClose(true);
        return executor;
    }

    @Bean static AgentTurnCapacityFilter agentTurnCapacityFilter(AgentTurnCapacity capacity,
                                                                 ObjectMapper mapper) {
        return new AgentTurnCapacityFilter(capacity, mapper);
    }

    @Bean static FilterRegistrationBean<AgentTurnCapacityFilter> disableDuplicateCapacityFilter(
            AgentTurnCapacityFilter filter) {
        // 只允许 SecurityConfig 把它放进安全链，不能再由 Servlet 容器执行一次。
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Override public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(executor);
        // 业务循环先退出，容器随后兜底；避免 Tomcat 默认三十秒截断三分钟的业务流。
        configurer.setDefaultTimeout(settings.getSseTimeout().plusSeconds(5).toMillis());
    }
}
