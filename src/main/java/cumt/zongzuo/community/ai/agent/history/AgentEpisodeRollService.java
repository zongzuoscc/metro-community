package cumt.zongzuo.community.ai.agent.history;

import cumt.zongzuo.community.ai.agent.turn.AgentRunGuardRecord;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在主对话过长时原子封存活动 episode，并建立下一个空 episode。
 *
 * <p>切换与 Agent 的用户级运行锁使用相同加锁顺序。只有没有活动生成任务时才能切换，
 * 因而旧 worker 不会把回答提交到已封存片段。摘要由 Worker 随后异步生成，不把外部模型
 * 请求放进数据库事务。</p>
 */
@Service
@ConditionalOnProperty(name = "metro.ai.memory.summary-enabled", havingValue = "true")
public class AgentEpisodeRollService {

    private final AgentTurnMapper mapper;
    private final TransactionTemplate transactions;
    private final int turnThreshold;

    public AgentEpisodeRollService(AgentTurnMapper mapper,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${metro.ai.memory.summary-turn-threshold:20}")
                                   int turnThreshold) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        if (turnThreshold < 4 || turnThreshold > 200) {
            throw new IllegalArgumentException("summary turn threshold is invalid");
        }
        this.turnThreshold = turnThreshold;
    }

    public boolean rollIfThresholdReached(long userId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            mapper.ensureGuard(userId);
            AgentRunGuardRecord guard = mapper.selectGuardForUpdate(userId);
            if (guard == null || guard.getActiveRunId() != null) return false;
            mapper.ensureConversation(userId);
            Long conversationId = mapper.selectConversationIdForUpdate(userId);
            if (conversationId == null) return false;
            mapper.ensureActiveEpisode(userId, conversationId);
            AgentEpisodeStats episode = mapper.selectActiveEpisodeStatsForUpdate(userId, conversationId);
            if (episode == null || episode.turnCount() < turnThreshold) return false;
            if (mapper.sealActiveEpisode(episode.id(), userId) != 1
                    || mapper.insertNextEpisode(userId, conversationId) != 1) {
                throw new IllegalStateException("Agent episode roll lost its write fence");
            }
            return true;
        }));
    }
}
