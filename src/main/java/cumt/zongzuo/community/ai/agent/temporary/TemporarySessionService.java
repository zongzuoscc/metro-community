package cumt.zongzuo.community.ai.agent.temporary;

import cumt.zongzuo.community.ai.agent.turn.AgentRunGuardRecord;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnMapper;
import cumt.zongzuo.community.ai.web.AiApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 使用全模式共享的用户级 run guard 协调 session 删除。
 * 删除不会隐式取消正在生成的回答，因为那会让用户难以区分“退出临时模式”与“取消任务”。
 */
@Service
public class TemporarySessionService {

    private final TemporarySessionStore sessions;
    private final TemporaryTurnStore turns;
    private final AgentTurnMapper mapper;
    private final TransactionTemplate transactions;

    public TemporarySessionService(TemporarySessionStore sessions, TemporaryTurnStore turns,
                                   AgentTurnMapper mapper,
                                   PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.turns = turns;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 仅当用户没有任何持久或临时 run 时删除可丢弃内容。
     * 先在 MySQL 行锁下校验 guard，再原子失效父 session，最后按 Redis 索引删除 turn/SSE/幂等键。
     * 父键必须最先消失，否则迟到 worker 会在子键刚删除后重建包含回答的 SSE Stream。
     */
    public void delete(long userId) {
        transactions.executeWithoutResult(status -> {
            mapper.ensureGuard(userId);
            AgentRunGuardRecord guard = mapper.selectGuardForUpdate(userId);
            if (guard != null && guard.getActiveRunId() != null
                    && "TEMPORARY".equals(guard.getActiveRunType())) {
                // 进程崩溃后不会有临时 recovery worker，删除入口也必须能回收已过期的 guard。
                mapper.releaseExpiredTemporaryGuard(userId, guard.getLockVersion());
                guard = mapper.selectGuardForUpdate(userId);
            }
            if (guard != null && guard.getActiveRunId() != null) {
                throw AiApiException.activeTurnExists();
            }
            TemporarySessionView session = sessions.current(userId);
            if (session != null && sessions.invalidate(userId, session.sessionId())) {
                turns.deleteSessionTurns(userId, session.sessionId());
                sessions.deleteChildren(userId, session.sessionId());
            }
        });
    }
}
