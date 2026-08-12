package cumt.zongzuo.community.ai.agent.turn;

import cumt.zongzuo.community.ai.web.AiApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgentTurnCancellationService {

    private final AgentTurnMapper mapper;
    private final AgentTurnEventStore events;
    private final AgentRunLeaseStore leases;
    private final TransactionTemplate transactions;

    public AgentTurnCancellationService(AgentTurnMapper mapper, AgentTurnEventStore events,
                                        AgentRunLeaseStore leases,
                                        PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.events = events;
        this.leases = leases;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AgentTurnSnapshot cancel(long turnId, long userId, AgentTurnQueryService queries) {
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
}
