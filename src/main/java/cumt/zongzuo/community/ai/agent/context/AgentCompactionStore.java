package cumt.zongzuo.community.ai.agent.context;

import cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit;
import java.util.List;
import java.util.UUID;

/** 持久压缩边界。每个方法只执行短数据库操作，禁止在事务里等待模型或 Milvus。 */
public interface AgentCompactionStore {
    Snapshot snapshot(long userId);
    Batch pending(long userId, int boundary, long afterTurnId);
    List<AgentConversationHistoryHit> recent(long userId, int boundary, int turns);
    List<AgentConversationHistoryHit> messages(long userId, int boundary, long afterTurnId,
                                             long beforeTurnId, int turns);
    Batch stage(long userId, UUID runId, Snapshot expected, long throughTurnId,
                AgentCompactionModel.Extraction extraction, List<AgentConversationHistoryHit> evidence);
    void activate(long userId, UUID runId, Batch batch);

    /** memoryEpoch/settingVersion 是模型调用前快照，写入时需重新校验，防止覆盖期间的用户操作。 */
    record Snapshot(long conversationId, int boundary, long memoryEpoch, boolean memoryEnabled,
                    long settingVersion, long coveredThroughTurnId, String summary) { }
    record Batch(long id, int boundary, long throughTurnId, String summary, boolean memoryEnabled) { }
}
