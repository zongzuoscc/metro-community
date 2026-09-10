package cumt.zongzuo.community.ai.agent.turn;

import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * SSE 连接的轻量唤醒器。正文始终从有界 Redis Stream 读取，通知只表示“可能有新事件”。
 * 每个连接最多保留一个待处理信号；慢客户端不会积累无界通知，也不会抢走其他客户端的事件。
 */
@Component
public class AgentTurnEventSignals {
    public static final String CHANNEL = "agent:turn:events:changed";
    private final Map<Long, Set<Subscription>> subscriptions = new HashMap<>();

    /** 在读 Stream 之前注册；提前到达的通知会保留，避免先查后等造成丢唤醒。 */
    public synchronized Subscription subscribe(long turnId) {
        var subscription = new Subscription(turnId);
        subscriptions.computeIfAbsent(turnId, ignored -> new HashSet<>()).add(subscription);
        return subscription;
    }

    /** 同一轮的所有连接都被唤醒；不携带正文、用户身份或 API Key。 */
    public synchronized void signal(long turnId) {
        var readers = subscriptions.get(turnId);
        if (readers != null) readers.forEach(Subscription::wake);
    }

    synchronized int subscriberCount() {
        return subscriptions.values().stream().mapToInt(Set::size).sum();
    }

    public final class Subscription implements AutoCloseable {
        private final long turnId;
        private final Semaphore wakeup = new Semaphore(0);
        private boolean closed;
        private Subscription(long turnId) { this.turnId = turnId; }

        private void wake() {
            // 调用方持有外层锁，因此多个生产者不会同时把许可从零增加成多个。
            if (!closed && wakeup.availablePermits() == 0) wakeup.release();
        }

        /** 超时用于补查丢失的 Pub/Sub 通知，不意味着事件丢失。虚拟线程在此等待不占平台线程。 */
        public boolean await(Duration timeout) throws InterruptedException {
            return wakeup.tryAcquire(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS);
        }

        /** 断开、终态、异常都必须清理注册，避免把已关闭浏览器永久留在内存。 */
        @Override public void close() {
            synchronized (AgentTurnEventSignals.this) {
                if (closed) return;
                closed = true;
                var readers = subscriptions.get(turnId);
                if (readers != null) {
                    readers.remove(this);
                    if (readers.isEmpty()) subscriptions.remove(turnId);
                }
                wakeup.release();
            }
        }
    }
}
