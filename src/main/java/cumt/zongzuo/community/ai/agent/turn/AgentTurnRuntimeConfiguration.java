package cumt.zongzuo.community.ai.agent.turn;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import cumt.zongzuo.community.ai.config.MetroAiProperties;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentTurnRuntimeProperties.class)
@ConditionalOnProperty(name = {"metro.ai.enabled", "metro.ai.agent.enabled"}, havingValue = "true")
class AgentTurnRuntimeConfiguration {

    @Bean
    AgentTurnCapacity agentTurnCapacity(AgentTurnRuntimeProperties settings, MetroAiProperties models) {
        return new AgentTurnCapacity(settings.effectiveWorkers(models.getAgent().getBulkhead())
                + settings.getQueueCapacity());
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService agentTurnExecutor(AgentTurnCapacity capacity, AgentTurnRuntimeProperties settings,
                                     MetroAiProperties models) {
        int workers = settings.effectiveWorkers(models.getAgent().getBulkhead());
        AtomicInteger sequence = new AtomicInteger();
        // 真正的总量由 capacity 控制。物理队列留到总容量，覆盖任务 finally 已释放
        // 名额、工作线程尚未取下一项的极短交接窗口，避免“有名额却随机拒绝”。
        ExecutorService pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers + settings.getQueueCapacity()), task -> {
            Thread thread = new Thread(task, "agent-turn-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        return new CapacityAwareTurnExecutor(pool, capacity);
    }

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService agentTurnHeartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "agent-turn-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}
