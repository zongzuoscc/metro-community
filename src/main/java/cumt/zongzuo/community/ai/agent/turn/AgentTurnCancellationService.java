package cumt.zongzuo.community.ai.agent.turn;

import cumt.zongzuo.community.ai.web.AiApiException;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnLifecycleService;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnRecord;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在 worker 使用的同一用户级 run fence 下取消 turn。
 *
 * <p>取消必须同时匹配 runId 和 runFence，以防止迟到的旧请求取消用户刚启动的新任务。</p>
 */
@Service
public class AgentTurnCancellationService {

    private final AgentTurnMapper mapper;
    private final AgentTurnEventStore events;
    private final AgentRunLeaseStore leases;
    private final TransactionTemplate transactions;
    private final TemporaryTurnStore temporaryTurns;
    private final TemporaryTurnLifecycleService temporaryLifecycle;

    public AgentTurnCancellationService(AgentTurnMapper mapper, AgentTurnEventStore events,
                                        AgentRunLeaseStore leases,
                                        TemporaryTurnStore temporaryTurns,
                                        TemporaryTurnLifecycleService temporaryLifecycle,
                                        PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.events = events;
        this.leases = leases;
        this.temporaryTurns = temporaryTurns;
        this.temporaryLifecycle = temporaryLifecycle;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 取消当前用户所有的 turn，并返回取消后快照。
     * 正 ID 走 MySQL 持久路径，负 ID 走 Redis 临时路径；两者都使用同一用户级 fence。
     */
    public AgentTurnSnapshot cancel(long turnId, long userId, AgentTurnQueryService queries) {
        if (turnId < 0) return cancelTemporary(turnId, userId, queries);
        AgentTurnRecord cancelled = transactions.execute(status -> {
            AgentRunGuardRecord guard = mapper.selectGuardForUpdate(userId);
            AgentTurnRecord turn = mapper.selectByIdForUpdate(turnId, userId);
            if (turn == null) {
                throw AiApiException.resourceNotFound();
            }
            if (!"RUNNING".equals(turn.getState()) && !"RECEIVED".equals(turn.getState())) {
                return turn;
            }
            if (guard == null || !turn.getRunId().equals(guard.getActiveRunId())
                    || turn.getRunFence() != guard.getRunFence()
                    || mapper.cancelTurn(turnId, userId, turn.getRunId(), turn.getRunFence()) != 1
                    || mapper.releaseGuard(userId, turn.getRunId(), turn.getRunFence()) != 1) {
                throw AiApiException.optimisticLockConflict();
            }
            turn.setState("CANCELLED");
            return turn;
        });
        if (cancelled != null && "CANCELLED".equals(cancelled.getState())) {
            leases.release(userId, cancelled.getRunId(), cancelled.getRunFence());
            events.append(turnId, userId, cancelled.getRunId(), cancelled.getRunFence(),
                    "cancelled", java.util.Map.of("partialRetained", false));
        }
        return queries.snapshot(turnId, userId);
    }

    /**
     * 取消只存在于 Redis 的临时 turn，但仍使用 MySQL 共享栅栏作为最终线性化点。
     * 这样可以保证临时与持久模式不会同时为同一用户执行。
     */
    private AgentTurnSnapshot cancelTemporary(long turnId, long userId,
                                              AgentTurnQueryService queries) {
        TemporaryTurnRecord turn = temporaryTurns.find(turnId, userId);
        if (turn == null) throw AiApiException.resourceNotFound();
        if ("RUNNING".equals(turn.state()) && !temporaryLifecycle.cancel(turn)) {
            throw AiApiException.optimisticLockConflict();
        }
        if ("RUNNING".equals(turn.state())) {
            events.append(turnId, userId, turn.runId(), turn.runFence(), "cancelled",
                    java.util.Map.of("partialRetained", false, "temporary", true));
        }
        return queries.snapshot(turnId, userId);
    }
}
