package cumt.zongzuo.community.ai.agent.turn;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class AgentTurnFailureService {

    private final AgentTurnMapper mapper;
    private final AgentRunLeaseStore leases;
    private final TransactionTemplate transactions;

    public AgentTurnFailureService(AgentTurnMapper mapper, AgentRunLeaseStore leases,
                                   PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.leases = leases;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public boolean fail(long turnId, long userId, UUID runId, long runFence, String errorCode) {
        Boolean failed = transactions.execute(status -> {
            AgentRunGuardRecord guard = mapper.selectGuardForUpdate(userId);
            AgentTurnRecord turn = mapper.selectByIdForUpdate(turnId, userId);
            if (guard == null || turn == null || !runId.equals(guard.getActiveRunId())
                    || guard.getRunFence() != runFence || !runId.equals(turn.getRunId())
                    || turn.getRunFence() != runFence || !"RUNNING".equals(turn.getState())) {
                return false;
            }
            return mapper.failTurn(turnId, userId, runId, runFence, errorCode) == 1
                    && mapper.releaseGuard(userId, runId, runFence) == 1;
        });
        if (Boolean.TRUE.equals(failed)) {
            leases.release(userId, runId, runFence);
        }
        return Boolean.TRUE.equals(failed);
    }
}
