package cumt.zongzuo.community.ai.agent.turn;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = {"metro.ai.enabled", "metro.ai.agent.enabled"}, havingValue = "true")
public class AgentTurnRecovery {

    private static final Duration LEASE = Duration.ofMinutes(2);

    private final AgentTurnMapper mapper;
    private final AgentRunLeaseStore leases;
    private final AgentTurnRunner runner;
    private final TransactionTemplate transactions;

    public AgentTurnRecovery(AgentTurnMapper mapper, AgentRunLeaseStore leases,
                             AgentTurnRunner runner, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.leases = leases;
        this.runner = runner;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Optional<AgentTurnAdmission> recoverOne() {
        Recovery recovery = transactions.execute(status -> {
            AgentTurnRecord turn = mapper.selectExpiredForUpdate();
            if (turn == null) {
                return null;
            }
            AgentRunGuardRecord guard = mapper.selectGuardForUpdate(turn.getUserId());
            if (guard == null || !turn.getRunId().equals(guard.getActiveRunId())
                    || turn.getRunFence() != guard.getRunFence()) {
                return null;
            }
            UUID runId = UUID.randomUUID();
            long fence = Math.addExact(guard.getRunFence(), 1);
            if (mapper.reclaimGuard(turn.getUserId(), turn.getRunId(), turn.getRunFence(),
                    runId, fence, LEASE.toSeconds()) != 1
                    || mapper.reclaimTurn(turn.getId(), turn.getUserId(), turn.getRunId(),
                    turn.getRunFence(), runId, fence, LEASE.toSeconds()) != 1) {
                throw new IllegalStateException("Agent stale turn reclaim fence was lost");
            }
            return new Recovery(new AgentTurnAdmission(turn.getId(), runId, fence, true, "RUNNING",
                    Boolean.TRUE.equals(turn.getWebSearchEnabled())),
                    turn.getUserId(), mapper.selectMessageContent(turn.getId(), turn.getUserId(), "USER"));
        });
        if (recovery == null) {
            return Optional.empty();
        }
        if (!leases.claim(recovery.userId(), recovery.admission().runId(),
                recovery.admission().runFence())) {
            return Optional.empty();
        }
        runner.submit(recovery.admission(), recovery.userId(), recovery.question());
        return Optional.of(recovery.admission());
    }

    public void recoverDueTurns() {
        for (int count = 0; count < 8 && mapper.countExpired() > 0; count++) {
            if (recoverOne().isEmpty()) {
                return;
            }
        }
    }

    private record Recovery(AgentTurnAdmission admission, long userId, String question) {
    }
}
