package cumt.zongzuo.community.ai.agent.memory;

import cumt.zongzuo.community.ai.web.AiApiException;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Agent 自动记忆写入入口。身份、运行权、消息来源和代际由服务端绑定，模型只提供低风险事实与原文引用。
 *
 * <p>外层短事务校验并持有运行/会话锁，再复用管理服务的同一事务保存版本、投影和 epoch。
 * 来源写入与正文一起提交；不调用 embedding。返回的 afterEpoch 只代表本次提交，不能用于吸收外部修改。</p>
 */
@Service
public class AgentMemoryToolService {
    private static final Set<String> CATEGORIES = Set.of("PREFERENCE", "GOAL", "PROFILE");
    private final AgentMemoryMapper mapper;
    private final AgentMemoryManagementService management;
    private final TransactionTemplate transactions;
    private final AgentMemorySafetyPolicy safety;

    public AgentMemoryToolService(AgentMemoryMapper mapper, AgentMemoryManagementService management,
            PlatformTransactionManager transactionManager, AgentMemorySafetyPolicy safety) {
        this.mapper = mapper;
        this.management = management;
        this.transactions = new TransactionTemplate(transactionManager);
        this.safety = safety;
    }

    public boolean enabled(long userId) {
        mapper.ensureSetting(userId);
        return Boolean.TRUE.equals(mapper.enabled(userId));
    }

    public Mutation add(long userId, UUID runId, long expectedEpoch, String category, String content, String sourceQuote) {
        validateCall(userId, runId, expectedEpoch, sourceQuote);
        validateContent(content);
        if (category == null || !CATEGORIES.contains(category)) throw AiApiException.validationFailed();
        String hash = AgentMemoryText.sha256(AgentMemoryText.normalize(content));
        return transactions.execute(status -> {
            Source source = lockAndValidate(userId, runId, expectedEpoch, sourceQuote);
            AgentMemoryView existing = mapper.findCurrentByHash(userId, hash);
            if (existing != null) return unchanged("ALREADY_EXISTS", existing, userId, expectedEpoch);
            // 唯一内容哈希包含旧版本/已删除版本，不能把重放的旧事实恢复为新记忆。
            if (mapper.contentHashCount(userId, hash) > 0) throw AiApiException.idempotencyConflict();
            AgentMemoryView saved = management.create(userId, category, content, null);
            attachSource(userId, saved.id(), source);
            return changed("ADDED", mapper.find(saved.id(), userId), userId, expectedEpoch, "PENDING");
        });
    }

    public Mutation update(long userId, UUID runId, long expectedEpoch, long memoryId, long expectedVersion,
                           String content, String sourceQuote) {
        validateCall(userId, runId, expectedEpoch, sourceQuote);
        validateTarget(memoryId, expectedVersion);
        validateContent(content);
        return transactions.execute(status -> {
            Source source = lockAndValidate(userId, runId, expectedEpoch, sourceQuote);
            AgentMemoryView current = currentVersion(userId, memoryId, expectedVersion);
            if (!"ACTIVE".equals(current.state()) || (current.expiresAt() != null
                    && !current.expiresAt().isAfter(LocalDateTime.now())))
                throw AiApiException.optimisticLockConflict();
            if (AgentMemoryText.normalize(current.content()).equals(AgentMemoryText.normalize(content)))
                return unchanged("UNCHANGED", current, userId, expectedEpoch);
            management.edit(userId, memoryId, content, expectedVersion);
            attachSource(userId, memoryId, source);
            return changed("UPDATED", mapper.find(memoryId, userId), userId, expectedEpoch, "PENDING");
        });
    }

    public Mutation delete(long userId, UUID runId, long expectedEpoch, long memoryId, long expectedVersion,
                           String sourceQuote) {
        validateCall(userId, runId, expectedEpoch, sourceQuote);
        validateTarget(memoryId, expectedVersion);
        return transactions.execute(status -> {
            lockAndValidate(userId, runId, expectedEpoch, sourceQuote);
            AgentMemoryView current = currentVersion(userId, memoryId, expectedVersion);
            if ("DELETED".equals(current.state()))
                return new Mutation("ALREADY_DELETED", null, expectedEpoch, expectedEpoch,
                        Objects.requireNonNullElse(mapper.projectionState(memoryId, userId), "UNKNOWN"));
            management.delete(userId, memoryId);
            return changed("DELETED", null, userId, expectedEpoch, "DELETING");
        });
    }

    private Source lockAndValidate(long userId, UUID runId, long expectedEpoch, String sourceQuote) {
        if (mapper.lockActiveAccountForMemoryTools(userId) == null) throw cancelled();
        Long fence = mapper.lockPersistentRunFence(userId, runId);
        if (fence == null || mapper.lockConversationForMemory(userId) == null) throw cancelled();
        mapper.ensureSetting(userId);
        if (!Boolean.TRUE.equals(mapper.lockMemoryEnabled(userId))) throw cancelled();
        Long turnId = mapper.lockPersistentTurnId(userId, runId, fence);
        if (turnId == null || !Objects.equals(mapper.lockMemoryEpoch(userId), expectedEpoch)) throw cancelled();
        Long messageId = mapper.sourceMessageId(turnId, userId);
        String original = messageId == null ? null : mapper.messageContent(messageId, userId);
        // 引用只验证真实原文来源，不把模型自报的 confirmation 或 sourceType 当成授权。
        if (original == null || !original.contains(sourceQuote)) throw AiApiException.validationFailed();
        // 等待会话锁期间时间仍会推进；取得全部业务锁后重验 guard，不能使用已过期的先前快照。
        if (!Objects.equals(mapper.lockPersistentRunFence(userId, runId), fence)) throw cancelled();
        return new Source(turnId, messageId);
    }

    private AgentMemoryView currentVersion(long userId, long memoryId, long expectedVersion) {
        AgentMemoryView current = mapper.findMutationTarget(memoryId, userId);
        if (current == null) throw AiApiException.resourceNotFound();
        if (current.version() != expectedVersion) throw AiApiException.optimisticLockConflict();
        return current;
    }

    private void attachSource(long userId, long memoryId, Source source) {
        Long versionId = mapper.currentVersionId(memoryId, userId);
        if (versionId == null || mapper.insertSource(userId, memoryId, versionId, source.turnId(), source.messageId()) != 1)
            throw new IllegalStateException("Memory source could not be attached to committed version");
    }

    private Mutation changed(String status, AgentMemoryView view, long userId, long beforeEpoch, String indexState) {
        long afterEpoch = Math.addExact(beforeEpoch, 1);
        if (!Objects.equals(mapper.lockMemoryEpoch(userId), afterEpoch))
            throw new IllegalStateException("Memory mutation did not advance its exact epoch");
        return new Mutation(status, view, beforeEpoch, afterEpoch, indexState);
    }

    private Mutation unchanged(String status, AgentMemoryView view, long userId, long epoch) {
        return new Mutation(status, view, epoch, epoch,
                Objects.requireNonNullElse(mapper.projectionState(view.id(), userId), "UNKNOWN"));
    }

    private void validateCall(long userId, UUID runId, long expectedEpoch, String quote) {
        if (userId <= 0 || runId == null || expectedEpoch < 0 || !safety.canStore(quote)
                || quote.codePointCount(0, quote.length()) < 4)
            throw AiApiException.validationFailed();
    }

    private void validateContent(String content) {
        if (!safety.canStore(content) || AgentMemoryText.normalize(content).isBlank())
            throw AiApiException.validationFailed();
    }

    private void validateTarget(long memoryId, long expectedVersion) {
        if (memoryId <= 0 || expectedVersion < 1) throw AiApiException.validationFailed();
    }

    private static CancellationException cancelled() {
        return new CancellationException("Memory operation lost its run, setting or epoch fence");
    }

    private record Source(long turnId, long messageId) {}

    public record Mutation(String status, AgentMemoryView view, long beforeEpoch, long afterEpoch, String indexState) {}
}
