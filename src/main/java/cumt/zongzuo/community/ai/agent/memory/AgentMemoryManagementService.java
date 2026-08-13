package cumt.zongzuo.community.ai.agent.memory;

import cumt.zongzuo.community.ai.web.AiApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 长期记忆的用户所有者 CRUD 边界，并管理“是否允许记忆”开关。
 * 所有查询与更新 SQL 都同时绑定 userId，避免仅凭 memoryId 跨用户访问。
 */
@Service
public class AgentMemoryManagementService {

    private static final Set<String> MANUAL_CATEGORIES = Set.of("PREFERENCE", "GOAL", "PROFILE");

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
        // 管理界面需要同时展示正在使用和已暂停的记忆；召回服务仍只读取 ACTIVE，二者不能混用。
        return mapper.listManaged(userId, 64);
    }

    public AgentMemoryView get(long userId, long memoryId) {
        AgentMemoryView memory = recall.find(userId, memoryId);
        if (memory == null) throw AiApiException.resourceNotFound();
        return memory;
    }

    /**
     * 手动添加与自动捕获共用相同的敏感信息拦截、内容去重和不可变版本结构。
     * 手动来源不伪造 conversation message；界面依据“是否存在 source 行”展示来源。
     */
    public AgentMemoryView create(long userId, String category, String content,
                                  LocalDateTime expiresAt) {
        String normalized = AgentMemoryCaptureService.normalize(content);
        validateManual(category, content, normalized, expiresAt);
        return transactions.execute(status -> {
            mapper.ensureSetting(userId);
            if (mapper.contentHashCount(userId, AgentMemoryCaptureService.sha256(normalized)) > 0) {
                throw AiApiException.idempotencyConflict();
            }
            AgentMemoryMapper.MemoryInsert item = new AgentMemoryMapper.MemoryInsert();
            item.userId = userId;
            item.category = category;
            item.expiresAt = expiresAt;
            mapper.insertItem(item);
            AgentMemoryMapper.MemoryVersionInsert version = new AgentMemoryMapper.MemoryVersionInsert();
            version.userId = userId;
            version.memoryId = item.id;
            version.versionNo = 1;
            version.content = content.strip();
            version.normalizedContent = normalized;
            version.contentHash = AgentMemoryCaptureService.sha256(normalized);
            mapper.insertVersion(version);
            if (mapper.activateVersion(item.id, userId, version.id) != 1) {
                throw new IllegalStateException("Manual memory activation lost its owner binding");
            }
            mapper.insertProjection(version.id, userId);
            mapper.incrementEpoch(userId);
            return mapper.find(item.id, userId);
        });
    }

    /** 更新绝对到期时间；null 表示由用户明确改为永不过期。 */
    public AgentMemoryView updateExpiry(long userId, long memoryId,
                                        LocalDateTime expiresAt, long expectedVersion) {
        validateExpiry(expiresAt);
        return transactions.execute(status -> {
            Long lockVersion = mapper.itemLockVersion(memoryId, userId);
            if (lockVersion == null) throw AiApiException.resourceNotFound();
            AgentMemoryView current = mapper.find(memoryId, userId);
            if (current == null) throw AiApiException.resourceNotFound();
            if (current.version() != expectedVersion) throw AiApiException.optimisticLockConflict();
            if (mapper.updateExpiry(memoryId, userId, expiresAt, lockVersion) != 1) {
                throw AiApiException.optimisticLockConflict();
            }
            mapper.incrementEpoch(userId);
            return mapper.find(memoryId, userId);
        });
    }

    private void validateManual(String category, String content, String normalized,
                                LocalDateTime expiresAt) {
        if (!MANUAL_CATEGORIES.contains(category) || normalized.isBlank()
                || !safety.canStore(content)) {
            throw AiApiException.validationFailed();
        }
        validateExpiry(expiresAt);
    }

    private void validateExpiry(LocalDateTime expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
            throw AiApiException.validationFailed();
        }
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

    /**
     * 暂停只切断后续召回，不删除内容或来源；恢复则重新允许召回。
     * 事务先锁定所有者自己的记忆，再校验用户看到的内容版本，避免旧页面覆盖并发编辑结果。
     */
    public AgentMemoryView updateState(long userId, long memoryId, boolean paused,
                                       long expectedVersion) {
        return transactions.execute(status -> {
            Long lockVersion = mapper.itemLockVersion(memoryId, userId);
            if (lockVersion == null) throw AiApiException.resourceNotFound();
            AgentMemoryView current = mapper.find(memoryId, userId);
            if (current == null) throw AiApiException.resourceNotFound();
            if (current.version() != expectedVersion) throw AiApiException.optimisticLockConflict();

            String targetState = paused ? "PAUSED" : "ACTIVE";
            if (targetState.equals(current.state())) return current;
            String expectedState = paused ? "ACTIVE" : "PAUSED";
            if (!expectedState.equals(current.state())
                    || mapper.updateState(memoryId, userId, expectedState, targetState, lockVersion) != 1) {
                throw AiApiException.optimisticLockConflict();
            }
            // 已在运行的 Agent 会在 Provider 调用前重验 epoch，因此暂停后不会继续使用旧召回结果。
            mapper.incrementEpoch(userId);
            return mapper.find(memoryId, userId);
        });
    }

    /** 首次读取也创建默认设置行，使前端总能获得可用于后续更新的确定版本。 */
    public MemorySettingView setting(long userId) {
        return transactions.execute(status -> {
            mapper.ensureSetting(userId);
            Boolean enabled = mapper.enabled(userId);
            Long version = mapper.settingVersion(userId);
            return new MemorySettingView(Boolean.TRUE.equals(enabled), version == null ? 0 : version);
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
