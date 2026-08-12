package cumt.zongzuo.community.ai.agent.turn;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

@Service
public class AgentTurnLeaseService {

    private static final long LEASE_SECONDS = Duration.ofMinutes(2).toSeconds();

    private final AgentRunLeaseStore redisLeases;
    private final AgentTurnMapper mapper;
    private final TransactionTemplate transactions;

    public AgentTurnLeaseService(AgentRunLeaseStore redisLeases, AgentTurnMapper mapper,
                                 PlatformTransactionManager transactionManager) {
        this.redisLeases = redisLeases;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public boolean renew(long turnId, long userId, UUID runId, long runFence) {
        if (!redisLeases.renew(userId, runId, runFence)) {
            return false;
        }
        Boolean renewed = transactions.execute(status -> {
            if (mapper.renewGuardLease(userId, runId, runFence, LEASE_SECONDS) != 1
                    || mapper.renewTurnLease(turnId, userId, runId, runFence,
                    LEASE_SECONDS) != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(renewed);
    }
}
