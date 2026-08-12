package cumt.zongzuo.community.ai.agent.memory;

import cumt.zongzuo.community.ai.web.AiApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 长期记忆的用户所有者 CRUD 边界，并管理“是否允许记忆”开关。
 * 所有查询与更新 SQL 都同时绑定 userId，避免仅凭 memoryId 跨用户访问。
 */
@Service
public class AgentMemoryManagementService {

    private final AgentMemoryMapper mapper;
    private final AgentMemoryRecallService recall;
    private final TransactionTemplate transactions;
    private final AgentMemorySafetyPolicy safety;

    public AgentMemoryManagementService(AgentMemoryMapper mapper, AgentMemoryRecallService recall,
                                        PlatformTransactionManager transactionManager,
                                        AgentMemorySafetyPolicy safety) {
        this.mapper = mapper;
        this.recall = recall;
        this.transactions = new TransactionTemplate(transactionManager);
        this.safety = safety;
    }

    public List<AgentMemoryView> list(long userId) {
        return recall.list(userId);
    }

    public AgentMemoryView get(long userId, long memoryId) {
        AgentMemoryView memory = recall.find(userId, memoryId);
        if (memory == null) throw AiApiException.resourceNotFound();
        return memory;
    }

    /**
     * 在乐观锁保护下追加新的不可变版本，而不是覆盖旧内容。
     * 旧版本转为 SUPERSEDED，对应向量投影进入删除流程，新版本使用 expectedVersion 防止丢失更新。
     */
    public AgentMemoryView edit(long userId, long memoryId, String content, long expectedVersion) {
        String normalized = AgentMemoryCaptureService.normalize(content);
        if (!safety.canStore(content) || normalized.isBlank()) {
            throw AiApiException.validationFailed();
        }
        return transactions.execute(status -> {
            Long lockVersion = mapper.itemLockVersion(memoryId, userId);
            if (lockVersion == null) throw AiApiException.resourceNotFound();
            AgentMemoryView current = mapper.find(memoryId, userId);
            if (current == null) throw AiApiException.resourceNotFound();
            if (current.version() != expectedVersion) throw AiApiException.optimisticLockConflict();
            if (mapper.contentHashCount(userId, AgentMemoryCaptureService.sha256(normalized)) > 0) {
                throw AiApiException.idempotencyConflict();
            }
            AgentMemoryMapper.MemoryVersionInsert version = new AgentMemoryMapper.MemoryVersionInsert();
            version.userId = userId;
            version.memoryId = memoryId;
            version.versionNo = Math.addExact(expectedVersion, 1);
            version.content = content.strip();
            version.normalizedContent = normalized;
            version.contentHash = AgentMemoryCaptureService.sha256(normalized);
            mapper.supersedeCurrent(memoryId, userId);
            mapper.deleteSupersededProjections(memoryId, userId);
            mapper.insertVersion(version);
            if (mapper.updateCurrent(memoryId, userId, version.id, lockVersion) != 1) {
                throw AiApiException.optimisticLockConflict();
            }
            mapper.insertProjection(version.id, userId);
            mapper.incrementEpoch(userId);
            return mapper.find(memoryId, userId);
        });
    }

    /** 删除用户可见记忆内容，并将所有派生投影置为待删除，防止向量索引继续召回已删除数据。 */
    public void delete(long userId, long memoryId) {
        transactions.executeWithoutResult(status -> {
            if (mapper.itemLockVersion(memoryId, userId) == null) {
                throw AiApiException.resourceNotFound();
            }
            if (mapper.deleteItem(memoryId, userId) != 1) {
                throw AiApiException.resourceNotFound();
            }
            mapper.deleteVersions(memoryId, userId);
            mapper.deleteAllProjections(memoryId, userId);
            mapper.incrementEpoch(userId);
        });
    }

    public MemorySettingView updateSetting(long userId, boolean enabled, long expectedVersion) {
        return transactions.execute(status -> {
            mapper.ensureSetting(userId);
            if (mapper.updateSetting(userId, enabled, expectedVersion) != 1) {
                throw AiApiException.optimisticLockConflict();
            }
            mapper.incrementEpoch(userId);
            return new MemorySettingView(enabled, expectedVersion + 1);
        });
    }

    public record MemorySettingView(boolean enabled, long version) {}
}
