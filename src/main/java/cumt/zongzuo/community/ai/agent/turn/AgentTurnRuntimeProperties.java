package cumt.zongzuo.community.ai.agent.turn;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** 整轮调度与 SSE 的部署预算；用户 Key 不能绕过进程的资源上限。 */
@ConfigurationProperties("metro.ai.turn-runtime")
public class AgentTurnRuntimeProperties {
    private int workers;
    private int queueCapacity = 64;
    private int sseConcurrency = 256;
    private Duration sseTimeout = Duration.ofMinutes(3);

    /** 0 表示跟随模型层并发，避免外层和内层两个预算各自为政。 */
    public int effectiveWorkers(int modelConcurrency) {
        int effective = workers == 0 ? modelConcurrency : workers;
        if (effective < 1 || effective > 256 || effective > modelConcurrency)
            throw new IllegalArgumentException("Turn workers must be within model concurrency and 1..256");
        if (queueCapacity < 0 || queueCapacity > 1024)
            throw new IllegalArgumentException("Turn queue capacity must be within 0..1024");
        if (sseConcurrency < 1 || sseConcurrency > 4096 || sseTimeout == null
                || sseTimeout.compareTo(Duration.ofSeconds(10)) < 0
                || sseTimeout.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("SSE limits are invalid");
        return effective;
    }
    public int getWorkers() { return workers; }
    public void setWorkers(int workers) { this.workers = workers; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int capacity) { this.queueCapacity = capacity; }
    public int getSseConcurrency() { return sseConcurrency; }
    public void setSseConcurrency(int concurrency) { this.sseConcurrency = concurrency; }
    public Duration getSseTimeout() { return sseTimeout; }
    public void setSseTimeout(Duration timeout) { this.sseTimeout = timeout; }
}
