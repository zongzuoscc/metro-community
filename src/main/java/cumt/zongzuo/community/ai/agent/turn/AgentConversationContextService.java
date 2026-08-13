package cumt.zongzuo.community.ai.agent.turn;

import cumt.zongzuo.community.ai.web.AiApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 为用户的唯一主对话切换新的模型上下文段。
 *
 * <p>“清空上下文”不删除历史、长期记忆或引用证据；它只封存当前 episode，
 * 让后续回答不再从旧 episode 中搜索对话内容。活动生成期间不允许切换，
 * 避免旧 worker 把结果提交到用户已经废弃的上下文中。</p>
 */
@Service
public class AgentConversationContextService {

    private final AgentTurnMapper mapper;
    private final TransactionTemplate transactions;

    public AgentConversationContextService(AgentTurnMapper mapper,
                                           PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void reset(long userId) {
        transactions.executeWithoutResult(status -> {
            mapper.ensureGuard(userId);
            AgentRunGuardRecord guard = mapper.selectGuardForUpdate(userId);
            if (guard == null || guard.getActiveRunId() != null) {
                throw AiApiException.activeTurnExists();
            }
            mapper.ensureConversation(userId);
            Long conversationId = mapper.selectConversationIdForUpdate(userId);
            if (conversationId == null) throw new IllegalStateException("Agent conversation is missing");
            mapper.ensureActiveEpisode(userId, conversationId);
            Long episodeId = mapper.selectActiveEpisodeIdForUpdate(userId, conversationId);
            if (episodeId == null || mapper.sealActiveEpisode(episodeId, userId) != 1
                    || mapper.insertNextEpisode(userId, conversationId) != 1) {
                throw AiApiException.optimisticLockConflict();
            }
        });
    }
}
