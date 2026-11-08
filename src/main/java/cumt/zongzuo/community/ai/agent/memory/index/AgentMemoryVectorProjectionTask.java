package cumt.zongzuo.community.ai.agent.memory.index;

import cumt.zongzuo.community.ai.agent.memory.AgentMemoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 从持久 PENDING/FAILED 投影队列有界恢复；不在用户回答线程构建记忆索引。 */
@Component
@ConditionalOnProperty(name = {"metro.ai.enabled", "metro.ai.embedding.enabled", "metro.ai.memory.enabled"}, havingValue = "true")
public class AgentMemoryVectorProjectionTask {
    private static final Logger log = LoggerFactory.getLogger(AgentMemoryVectorProjectionTask.class);
    private final AgentMemoryMapper mapper;
    private final AgentMemoryVectorService service;
    private final Clock clock;
    private final Duration timeout;
    private final String model;
    private final int pageSize;
    private long afterVersionId;

    public AgentMemoryVectorProjectionTask(AgentMemoryMapper mapper, AgentMemoryVectorService service,
                                           Clock clock,
                                           @Value("${metro.ai.memory.milvus.projection-timeout:PT30S}") Duration timeout,
                                           @Value("${metro.ai.embedding.model:bge-m3}") String model,
                                           @Value("${metro.ai.memory.milvus.page-size:32}") int pageSize) {
        this.mapper = Objects.requireNonNull(mapper);
        this.service = Objects.requireNonNull(service);
        this.clock = Objects.requireNonNull(clock);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Projection timeout must be positive");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("Embedding model is required");
        if (pageSize < 1 || pageSize > 128) throw new IllegalArgumentException("Page size must be 1..128");
        this.model = model;
        this.pageSize = pageSize;
    }

    @Scheduled(fixedDelayString = "${metro.ai.memory.milvus.projection-delay-ms:5000}")
    public synchronized void projectPending() {
        Instant deadline = clock.instant().plus(timeout);
        List<MemoryProjectionRow> page = mapper.listPendingVectorProjections(model, afterVersionId, pageSize);
        if (page.isEmpty()) {
            afterVersionId = 0;
            return;
        }
        if (page.size() > pageSize) throw new IllegalStateException("Memory projection page exceeds limit");
        long previous = afterVersionId;
        for (MemoryProjectionRow row : page) {
            if (row.userId() <= 0 || row.memoryVersionId() <= previous)
                throw new IllegalStateException("Memory projection page lost cursor ordering");
            previous = row.memoryVersionId();
        }
        int start = 0;
        while (start < page.size() && clock.instant().isBefore(deadline) && !Thread.currentThread().isInterrupted()) {
            long owner = page.get(start).userId();
            int end = start + 1;
            // 只合并相邻所有者，保持全局版本顺序；截止时间到达后不能跳过未尝试的其他用户。
            while (end < page.size() && page.get(end).userId() == owner) end++;
            List<MemoryProjectionRow> batch = page.subList(start, end);
            afterVersionId = batch.getLast().memoryVersionId();
            try {
                service.projectBatch(owner, batch, deadline);
            } catch (RuntimeException failure) {
                // 推进游标使失败用户不会阻塞后续用户；扫描到尾回绕，重启也从持久状态重试。
                log.warn("Memory vector projection deferred: userId={}, code=VECTOR_PROJECT_RETRY", owner);
            }
            start = end;
        }
    }
}
