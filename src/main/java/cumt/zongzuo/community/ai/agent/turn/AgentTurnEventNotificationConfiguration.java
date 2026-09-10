package cumt.zongzuo.community.ai.agent.turn;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 所有 SSE 共用一个 Redis 订阅连接，避免每个浏览器执行阻塞 XREAD 占住一个 Redis 连接。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"metro.ai.enabled", "metro.ai.agent.enabled"}, havingValue = "true")
class AgentTurnEventNotificationConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService agentEventNotificationExecutor() {
        // 通知允许丢失：Redis Stream 才是恢复依据，五秒补查保证最终可见。
        // 因此过载时丢通知而不是无限排队或阻塞 Redis 的订阅线程。
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1024), task -> {
                    var thread = new Thread(task, "agent-event-notify");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.DiscardPolicy());
    }

    @Bean
    RedisMessageListenerContainer agentEventNotificationContainer(
            RedisConnectionFactory connections, AgentTurnEventSignals signals,
            @org.springframework.beans.factory.annotation.Qualifier("agentEventNotificationExecutor")
            ExecutorService executor) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.setTaskExecutor(executor);
        container.addMessageListener((message, pattern) -> {
            try {
                signals.signal(Long.parseLong(new String(message.getBody(), StandardCharsets.UTF_8)));
            } catch (NumberFormatException ignored) {
                // 消息仅接受十进制 turnId；外部畸形通知不能变成正文或影响已接纳任务。
            }
        }, new ChannelTopic(AgentTurnEventSignals.CHANNEL));
        return container;
    }
}
