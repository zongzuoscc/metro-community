package cumt.zongzuo.community.ai.agent.memory.index;

import cumt.zongzuo.community.ai.agent.memory.AgentMemoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.List;
import java.util.Objects;

/** 不依赖用户启用设置的派生向量删除任务。 */
@Component
@ConditionalOnProperty(name = {"metro.ai.enabled", "metro.ai.embedding.enabled", "metro.ai.memory.enabled"}, havingValue = "true")
public class AgentMemoryVectorCleanupTask {
    private static final Logger log = LoggerFactory.getLogger(AgentMemoryVectorCleanupTask.class);
    private final AgentMemoryMapper mapper;
    private final AgentMemoryVectorService service;
    private final Clock clock;
    private final Duration timeout;
    private long afterUserId;
    private long afterTombstoneVersionId;

    public AgentMemoryVectorCleanupTask(AgentMemoryMapper mapper, AgentMemoryVectorService service,
                                        Clock clock,
                                        @Value("${metro.ai.memory.milvus.cleanup-timeout:PT30S}") Duration timeout) {
        this.mapper = Objects.requireNonNull(mapper);
        this.service = Objects.requireNonNull(service);
        this.clock = Objects.requireNonNull(clock);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Cleanup timeout must be positive");
    }

    @Scheduled(fixedDelayString = "${metro.ai.memory.milvus.cleanup-delay-ms:60000}")
    public synchronized void cleanup() {
        Instant deadline = clock.instant().plus(timeout);
        sweepRetiredTombstones(deadline);
        if (!clock.instant().isBefore(deadline)) return;
        List<Long> owners = mapper.listVectorDeletionUsers(afterUserId, 32);
        if (owners.isEmpty()) {
            afterUserId = 0;
            return;
        }
        for (long owner : owners) {
            if (!clock.instant().isBefore(deadline)) return;
            afterUserId = owner;
            try {
                service.deletePending(owner, deadline);
            } catch (RuntimeException failure) {
                // 下一轮游标回绕后重试；只写固定错误码，不打印潜在敏感 Provider 响应。
                log.warn("Memory vector cleanup deferred: userId={}, code=VECTOR_DELETE_RETRY", owner);
            }
        }
    }

    /** 跨库没有原子提交，写者崩溃后只能靠持续巡检墓碑修复迟到写入，不能仅依赖 catch。 */
    private void sweepRetiredTombstones(Instant deadline) {
        List<MemoryVectorTombstone> tombstones = mapper.listRetiredVectorTombstones(afterTombstoneVersionId, 32);
        if (tombstones.isEmpty()) {
            afterTombstoneVersionId = 0;
            return;
        }
        for (MemoryVectorTombstone tombstone : tombstones) {
            if (!clock.instant().isBefore(deadline)) return;
            afterTombstoneVersionId = tombstone.memoryVersionId();
            try {
                mapper.requeueRetiredVectorTombstone(tombstone.memoryVersionId(), tombstone.userId(), tombstone.lockVersion());
            } catch (RuntimeException failure) {
                // 即使本次数据库写入失败，游标回绕后仍会再次检查该墓碑。
                log.warn("Memory vector tombstone sweep deferred: code=VECTOR_TOMBSTONE_RETRY");
            }
        }
    }
}
