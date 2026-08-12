package cumt.zongzuo.community.ai.agent.temporary;

import cumt.zongzuo.community.ai.agent.GroundedAgentAnswer;
import cumt.zongzuo.community.ai.agent.turn.AgentRunGuardRecord;
import cumt.zongzuo.community.ai.agent.turn.AgentRunLeaseStore;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnMapper;
import cumt.zongzuo.community.ai.web.AiApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

/**
 * 在 Redis 中终结临时 turn 时，维护与持久模式共用的 MySQL fence。
 *
 * <p>Redis 保存内容，MySQL 决定谁还拥有运行权。完成、失败和取消都必须先锁定 guard 并校验
 * runId/runFence，再改 Redis 状态和释放 guard，防止旧 worker 的迟到回调覆盖新任务。</p>
 */
@Service
public class TemporaryTurnLifecycleService {

    private static final long LEASE_SECONDS = Duration.ofMinutes(2).toSeconds();

    private final TemporaryTurnStore turns;
    private final AgentTurnMapper mapper;
    private final AgentRunLeaseStore leases;
    private final TransactionTemplate transactions;

    public TemporaryTurnLifecycleService(TemporaryTurnStore turns, AgentTurnMapper mapper,
                                         AgentRunLeaseStore leases,
                                         PlatformTransactionManager transactionManager) {
        this.turns = turns;
        this.mapper = mapper;
        this.leases = leases;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 先续约 Redis 租约，再续约匹配的 MySQL guard。
     * Redis 续约失败时不再延长 MySQL，以便后续更高 fence 能回收运行权。
     */
    public boolean renew(long userId, UUID runId, long fence) {
        if (!leases.renew(userId, runId, fence)) return false;
        return Boolean.TRUE.equals(transactions.execute(status ->
                mapper.renewGuardLease(userId, runId, fence, LEASE_SECONDS) == 1));
    }

    /** 持有 guard 行锁时完成 Redis turn，然后释放 guard；两者之间不会被新 run 插入。 */
    public boolean complete(TemporaryTurnAdmission admission, long userId,
                            GroundedAgentAnswer answer) {
        Boolean completed = transactions.execute(status -> {
            if (!ownsGuard(userId, admission.runId(), admission.runFence())) return false;
            if (!turns.complete(admission.turnId(), userId, admission.runId(),
                    admission.runFence(), answer.answer(), answer.citations().size())) return false;
            return mapper.releaseGuard(userId, admission.runId(), admission.runFence()) == 1;
        });
        if (Boolean.TRUE.equals(completed)) leases.release(userId, admission.runId(), admission.runFence());
        return Boolean.TRUE.equals(completed);
    }

    /**
     * 使用稳定错误码终结 Redis turn，并释放匹配 guard。
     * 如果恰好在 24 小时边界 session 先过期，内容已不可恢复，但 guard 仍必须释放。
     */
    public boolean fail(TemporaryTurnAdmission admission, long userId, String errorCode) {
        Boolean failed = transactions.execute(status -> {
            if (!ownsGuard(userId, admission.runId(), admission.runFence())) return false;
            try {
                if (!turns.fail(admission.turnId(), userId, admission.runId(), admission.runFence(),
                        errorCode)) return false;
            } catch (AiApiException expired) {
                if (!"TEMPORARY_SESSION_EXPIRED".equals(expired.code())) throw expired;
                // 可丢弃内容已经过期；继续释放 guard，避免 24 小时边界将用户下一个持久/临时 turn 永久阻塞。
            }
            return mapper.releaseGuard(userId, admission.runId(), admission.runFence()) == 1;
        });
        if (Boolean.TRUE.equals(failed)) leases.release(userId, admission.runId(), admission.runFence());
        return Boolean.TRUE.equals(failed);
    }

    /** 在精确 fence 下取消当前临时 run，并释放 MySQL guard 与 Redis 租约。 */
    public boolean cancel(TemporaryTurnRecord turn) {
        Boolean cancelled = transactions.execute(status -> {
            if (!ownsGuard(turn.userId(), turn.runId(), turn.runFence())) return false;
            if (!turns.cancel(turn.turnId(), turn.userId(), turn.runId(), turn.runFence())) {
                return false;
            }
            return mapper.releaseGuard(turn.userId(), turn.runId(), turn.runFence()) == 1;
        });
        if (Boolean.TRUE.equals(cancelled)) {
            leases.release(turn.userId(), turn.runId(), turn.runFence());
        }
        return Boolean.TRUE.equals(cancelled);
    }

    private boolean ownsGuard(long userId, UUID runId, long fence) {
        AgentRunGuardRecord guard = mapper.selectGuardForUpdate(userId);
        return guard != null && runId.equals(guard.getActiveRunId())
                && fence == guard.getRunFence() && "TEMPORARY".equals(guard.getActiveRunType());
    }
}
