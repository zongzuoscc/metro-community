package cumt.zongzuo.community.ai.agent.memory.index;

import cumt.zongzuo.community.ai.agent.memory.*;
import cumt.zongzuo.community.ai.provider.*;
import cumt.zongzuo.community.ai.runtime.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;

/**
 * 持久记忆向量同步与召回边界。MySQL 为唯一事实源，Milvus 为可重建派生索引。
 * 调用者须先提交 PENDING 事务；失败必须上抛，不能用词法召回掩盖索引未完成。
 */
@Service
@ConditionalOnProperty(name = {"metro.ai.enabled", "metro.ai.embedding.enabled", "metro.ai.memory.enabled"}, havingValue = "true")
public class AgentMemoryVectorService {
    private final AgentMemoryMapper mapper;
    private final MemoryVectorRepository repository;
    private final AiCapabilityExecutor executor;
    private final EmbeddingGateway embedding;
    private final AgentMemorySafetyPolicy safety;
    private final Clock clock;
    private final Duration timeout;
    private final String model;
    private final int pageSize;

    public AgentMemoryVectorService(AgentMemoryMapper mapper, MemoryVectorRepository repository,
                                    AiCapabilityExecutor executor, EmbeddingGateway embedding,
                                    AgentMemorySafetyPolicy safety, Clock clock,
                                    @Value("${metro.ai.embedding.timeout:PT45S}") Duration timeout,
                                    @Value("${metro.ai.embedding.model:bge-m3}") String model,
                                    @Value("${metro.ai.memory.milvus.page-size:32}") int pageSize) {
        this.mapper = Objects.requireNonNull(mapper);
        this.repository = Objects.requireNonNull(repository);
        this.executor = Objects.requireNonNull(executor);
        this.embedding = Objects.requireNonNull(embedding);
        this.safety = Objects.requireNonNull(safety);
        this.clock = Objects.requireNonNull(clock);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        // MySQL 与既有 Milvus schema 均为 64 字节，不允许截断后混淆模型身份。
        if (model == null || model.isBlank() || model.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64)
            throw new IllegalArgumentException("embedding model must fit 64 bytes");
        this.model = model;
        if (pageSize < 1 || pageSize > 128) throw new IllegalArgumentException("page size must be 1..128");
        this.pageSize = pageSize;
    }

    public void synchronize(long userId, Instant deadline) {
        requireOutsideTransaction();
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        checkDeadline(deadline);
        if (!Boolean.TRUE.equals(mapper.enabled(userId))) {
            deletePending(userId, deadline);
            return;
        }
        long after = 0;
        while (true) {
            checkDeadline(deadline);
            if (!Boolean.TRUE.equals(mapper.enabled(userId)))
                throw new IllegalStateException("Memory setting changed during synchronization");
            List<MemoryProjectionRow> page = mapper.listVectorWork(userId, model, after, pageSize);
            checkDeadline(deadline);
            if (page.isEmpty()) return;
            if (page.size() > pageSize) throw new IllegalStateException("Memory projection page exceeds limit");
            for (MemoryProjectionRow row : page) {
                if (row.userId() != userId || row.memoryVersionId() <= after)
                    throw new IllegalStateException("Memory projection page lost owner or cursor ordering");
                after = row.memoryVersionId();
            }
            List<MemoryProjectionRow> deleting = page.stream().filter(row -> "DELETING".equals(row.state())).toList();
            if (!deleting.isEmpty()) {
                repository.deleteAndVerify(userId, deleting.stream().map(MemoryProjectionRow::memoryVersionId).toList(), deadline);
                checkDeadline(deadline);
                for (MemoryProjectionRow row : deleting) {
                    if (mapper.markVectorDeleted(row.memoryVersionId(), userId, row.lockVersion()) != 1)
                        throw new IllegalStateException("Memory projection changed during deletion");
                }
            }
            List<MemoryProjectionRow> pending = page.stream().filter(row -> !"DELETING".equals(row.state())).toList();
            if (!pending.isEmpty()) project(userId, pending, deadline);
        }
    }

    /** 删除队列独立入口，供关闭记忆后及后台清理使用。 */
    public void deletePending(long userId, Instant deadline) {
        requireOutsideTransaction();
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        long after = 0;
        while (true) {
            checkDeadline(deadline);
            List<MemoryProjectionRow> rows = mapper.listVectorDeletes(userId, after, pageSize);
            checkDeadline(deadline);
            if (rows.isEmpty()) return;
            if (rows.size() > pageSize) throw new IllegalStateException("Memory deletion page exceeds limit");
            for (MemoryProjectionRow row : rows) {
                if (row.userId() != userId || row.memoryVersionId() <= after || !"DELETING".equals(row.state()))
                    throw new IllegalStateException("Memory deletion page lost owner or cursor ordering");
                after = row.memoryVersionId();
            }
            repository.deleteAndVerify(userId, rows.stream().map(MemoryProjectionRow::memoryVersionId).toList(), deadline);
            checkDeadline(deadline);
            for (MemoryProjectionRow row : rows) {
                if (mapper.markVectorDeleted(row.memoryVersionId(), userId, row.lockVersion()) != 1)
                    throw new IllegalStateException("Memory projection changed during deletion");
            }
        }
    }

    public List<AgentMemoryView> recall(long userId, String question, int limit, Instant deadline) {
        requireOutsideTransaction();
        if (limit < 1 || limit > 256) throw new IllegalArgumentException("recall limit must be 1..256");
        if (question == null || question.isBlank()) return List.of();
        synchronize(userId, deadline);
        if (!Boolean.TRUE.equals(mapper.enabled(userId))) return List.of();
        float[] query = embed(userId, List.of(question.strip()), deadline).getFirst();
        int candidates = Math.min(1024, limit * 4);
        List<Long> ids = repository.search(userId, model, query, candidates, deadline);
        Map<Long, AgentMemoryView> selected = new LinkedHashMap<>();
        Map<Long, Long> selectedVersions = new LinkedHashMap<>();
        for (long versionId : new LinkedHashSet<>(ids)) {
            checkDeadline(deadline);
            AgentMemoryView memory = mapper.findVectorRecall(versionId, userId, model);
            if (memory != null && safety.canStore(memory.content())) {
                selected.putIfAbsent(memory.id(), memory);
                selectedVersions.putIfAbsent(memory.id(), versionId);
            }
            if (selected.size() == limit) break;
        }
        checkDeadline(deadline);
        if (!Boolean.TRUE.equals(mapper.enabled(userId))) return List.of();
        // 前面逐条校验期间可能发生删除/替换；返回前再次按不可变版本 ID 验证。
        List<AgentMemoryView> current = new ArrayList<>();
        for (long versionId : selectedVersions.values()) {
            checkDeadline(deadline);
            AgentMemoryView memory = mapper.findVectorRecall(versionId, userId, model);
            if (memory != null && safety.canStore(memory.content())) current.add(memory);
        }
        checkDeadline(deadline);
        return Boolean.TRUE.equals(mapper.enabled(userId)) ? List.copyOf(current) : List.of();
    }

    private void project(long userId, List<MemoryProjectionRow> rows, Instant deadline) {
        boolean upsertAttempted = false;
        try {
            for (MemoryProjectionRow row : rows) {
                if (!"LOW".equals(row.sensitivity()) || !safety.canStore(row.content()))
                    throw new IllegalStateException("Memory projection rejected by safety policy");
            }
            List<float[]> vectors = embed(userId, rows.stream().map(MemoryProjectionRow::content).toList(), deadline);
            List<MemoryVectorDocument> documents = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) documents.add(new MemoryVectorDocument(rows.get(i), model, vectors.get(i)));
            upsertAttempted = true;
            repository.upsertAndVerify(documents, deadline);
            checkDeadline(deadline);
            for (MemoryProjectionRow row : rows) {
                if (mapper.markVectorProjected(row.memoryVersionId(), userId, row.lockVersion(), model) != 1)
                    throw new IllegalStateException("Memory projection changed during synchronization");
            }
        } catch (RuntimeException failure) {
            // 仅保存固定错误码，禁止把 Provider 响应、正文或凭据写入错误字段。
            for (MemoryProjectionRow row : rows) {
                if (upsertAttempted) {
                    // 清理实例可能已确认 DELETED，但本次迟到写入又落到了 Milvus。
                    // SQL 按实时归属和版本生命周期重新入队，并递增锁版本使旧删除确认失效。
                    // 即使 RPC 报错也不能假设没有落库；修复队列必须独立于普通错误标记执行。
                    try { mapper.requeueRetiredVector(row.memoryVersionId(), userId); }
                    catch (RuntimeException requeueFailure) { failure.addSuppressed(requeueFailure); }
                }
                try {
                    mapper.markVectorFailed(row.memoryVersionId(), userId, row.lockVersion(), "VECTOR_SYNC_FAILED");
                    mapper.invalidateVectorModelConflict(row.memoryVersionId(), userId, model);
                }
                catch (RuntimeException markingFailure) { failure.addSuppressed(markingFailure); }
            }
            throw failure;
        }
    }

    private List<float[]> embed(long userId, List<String> inputs, Instant deadline) {
        checkDeadline(deadline);
        Instant ownDeadline = clock.instant().plus(timeout);
        Instant effectiveDeadline = ownDeadline.isBefore(deadline) ? ownDeadline : deadline;
        int characters = inputs.stream().mapToInt(String::length).reduce(0, Math::addExact);
        EmbeddingResult result = executor.execute(new AiInvocationContext(AiCapability.EMBEDDING,
                        userId, "memory-persistent-vector", characters, effectiveDeadline, false),
                () -> embedding.embed(new EmbeddingCommand(AiCapability.EMBEDDING, inputs)));
        checkDeadline(effectiveDeadline);
        if (result == null || !model.equals(result.model()) || result.vectors().size() != inputs.size())
            throw new IllegalStateException("Memory embedding result is incompatible");
        List<float[]> vectors = result.vectors();
        vectors.forEach(AgentMemoryVectorService::requireVector);
        return vectors;
    }

    static void requireVector(float[] vector) {
        if (vector == null || vector.length != 1024) throw new IllegalStateException("Memory embedding must contain 1024 dimensions");
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Memory embedding must be finite");
            norm += (double) value * value;
        }
        if (norm == 0) throw new IllegalStateException("Memory embedding must have nonzero norm");
    }

    private void checkDeadline(Instant deadline) {
        if (deadline == null || !clock.instant().isBefore(deadline) || Thread.currentThread().isInterrupted())
            throw new IllegalStateException("Memory vector deadline exceeded");
    }

    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Memory vector network calls must run outside a SQL transaction");
    }
}
