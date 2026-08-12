package cumt.zongzuo.community.ai.agent.memory;

import cumt.zongzuo.community.ai.web.AiApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

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
