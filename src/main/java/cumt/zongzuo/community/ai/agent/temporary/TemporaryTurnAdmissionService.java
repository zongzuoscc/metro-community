package cumt.zongzuo.community.ai.agent.temporary;

import cumt.zongzuo.community.ai.agent.turn.AgentRunGuardRecord;
import cumt.zongzuo.community.ai.agent.turn.AgentRunLeaseStore;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnAdmissionService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnMapper;
import cumt.zongzuo.community.ai.web.AiApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 接纳临时 turn，但不创建 conversation、episode、message 或 turn 内容行。
 *
 * <p>MySQL 中的 agent_run_guard 仍然是最终权威，所以同一用户的持久与临时生成不能并行。
 * Redis 只保存可丢弃的请求、回答和短期历史。</p>
 *
 * <p>接纳顺序为：先验证 session 并快速处理幂等重放，然后在 MySQL 用户栅栏行锁内再次校验幂等索引，
 * 申请 Redis 租约并以 Lua 原子创建 Redis turn。行锁要保持到 Redis 幂等索引可见，才能保证两个并发相同请求
 * 返回同一个 turn，而不是其中一个误报 ACTIVE_TURN_EXISTS。</p>
 */
@Service
public class TemporaryTurnAdmissionService {

    private static final long LEASE_SECONDS = Duration.ofMinutes(2).toSeconds();

    private final TemporarySessionStore sessions;
    private final TemporaryTurnStore turns;
    private final AgentTurnMapper mapper;
    private final AgentRunLeaseStore leases;
    private final TransactionTemplate transactions;

    public TemporaryTurnAdmissionService(TemporarySessionStore sessions, TemporaryTurnStore turns,
                                         AgentTurnMapper mapper, AgentRunLeaseStore leases,
                                         PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.turns = turns;
        this.mapper = mapper;
        this.leases = leases;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 占用共享运行槽并且只创建可丢弃的 Redis turn 状态。
     * 同一 clientRequestId 的内容完全一致时返回原 turn；内容已改变时返回幂等冲突。
     */
    public TemporaryTurnAdmission admit(long userId, UUID sessionId, UUID requestId,
                                        String message, String pageContextJson) {
        return admit(userId, sessionId, requestId, message, pageContextJson, true);
    }

    public TemporaryTurnAdmission admit(long userId, UUID sessionId, UUID requestId,
                                        String message, String pageContextJson,
                                        boolean webSearchEnabled) {
        sessions.require(userId, sessionId);
        String requestHash = AgentTurnAdmissionService.sha256(
                message + "\n" + pageContextJson + "\nCOMMUNITY_QA\n" + webSearchEnabled);
        TemporaryTurnRecord existing = turns.findByRequest(userId, sessionId, requestId);
        if (existing != null) {
            if (!requestHash.equals(existing.requestHash())) {
                throw AiApiException.idempotencyConflict();
            }
            // 终态可以直接幂等返回；RUNNING 必须进入 MySQL 行锁内核对共享栕栏，
            // 否则无法区分“真正在运行”与“Redis 创建成功但客户端丢失返回值”。
            if (!"RUNNING".equals(existing.state())) return admission(existing, false);
        }
        AtomicReference<TemporaryTurnAdmission> created = new AtomicReference<>();
        AtomicReference<GuardClaim> claimed = new AtomicReference<>();
        try {
            TemporaryTurnAdmission result = transactions.execute(status -> {
                mapper.ensureGuard(userId);
                AgentRunGuardRecord guard = mapper.selectGuardForUpdate(userId);
                if (guard == null) throw new IllegalStateException("Agent run guard is missing");
                // 必须在行锁内重读幂等索引：前一个请求可能刚在等锁期间完成原子创建。
                TemporaryTurnRecord concurrent = turns.findByRequest(userId, sessionId, requestId);
                if (concurrent != null) {
                    if (!requestHash.equals(concurrent.requestHash())) {
                        throw AiApiException.idempotencyConflict();
                    }
                    if (!"RUNNING".equals(concurrent.state())) return admission(concurrent, false);

                    boolean guardMatches = concurrent.runId().equals(guard.getActiveRunId())
                            && concurrent.runFence() == guard.getRunFence()
                            && "TEMPORARY".equals(guard.getActiveRunType());
                    if (guardMatches
                            && mapper.releaseExpiredTemporaryGuard(userId, guard.getLockVersion()) != 1) {
                        // 栕栏仍有效，说明这是正常并发重放，返回同一个运行中 turn。
                        return admission(concurrent, false);
                    }
                    // 栕栏已过期或不再指向该 turn：它不可能再被 worker 合法完成。
                    // 将幂等结果稳定地终结为 FAILED，避免同一 clientRequestId 卡到 session 过期。
                    turns.fail(concurrent.turnId(), userId, concurrent.runId(),
                            concurrent.runFence(), "AGENT_ADMISSION_UNCERTAIN");
                    TemporaryTurnRecord failed = turns.findByRequest(userId, sessionId, requestId);
                    if (failed == null) throw AiApiException.temporarySessionExpired();
                    return admission(failed, false);
                }
                GuardClaim claim = claimGuard(userId, guard);
                if (!leases.claim(userId, claim.runId(), claim.fence())) {
                    throw AiApiException.runtimeUnavailable(Duration.ofSeconds(1));
                }
                claimed.set(claim);
                long turnId = turns.nextTurnId();
                // 先保存精确补偿句柄，再进入 Lua。这个顺序专门覆盖“服务端已提交，
                // 客户端在收到返回包前断线”的不确定结果；补偿仍会用 runId + fence 精确限定。
                TemporaryTurnAdmission value = new TemporaryTurnAdmission(turnId, sessionId,
                        claim.runId(), claim.fence(), true, "RUNNING", webSearchEnabled);
                created.set(value);
                TemporaryTurnRecord turn = turns.create(turnId, userId, sessionId, requestId,
                        claim.runId(), claim.fence(), requestHash, message, webSearchEnabled);
                if (turn.webSearchEnabled() != webSearchEnabled) {
                    throw new IllegalStateException("Temporary web search preference was not frozen");
                }
                value = admission(turn, true);
                return value;
            });
            if (result == null) throw new IllegalStateException(
                    "Temporary admission transaction returned no result");
            return result;
        } catch (RuntimeException error) {
            TemporaryTurnAdmission partial = created.get();
            if (partial != null) {
                // Redis 原子创建已完成但 MySQL commit 失败时，把幂等结果终结为失败，不留永久 RUNNING。
                try {
                    turns.fail(partial.turnId(), userId, partial.runId(), partial.runFence(),
                            "AGENT_RUNTIME_UNAVAILABLE");
                } catch (RuntimeException ignored) {
                    // session 同时删除/过期时已无内容泄漏风险，仍需继续释放租约。
                }
            }
            GuardClaim claim = claimed.get();
            if (claim != null) compensate(userId, claim.runId(), claim.fence());
            throw error;
        }
    }

    /** 在 Redis turn 尚未创建或需要通用回滚时，释放 MySQL 共享栅栏和 Redis 租约。 */
    public void compensate(long userId, UUID runId, long fence) {
        transactions.executeWithoutResult(status -> mapper.releaseGuard(userId, runId, fence));
        leases.release(userId, runId, fence);
    }

    /**
     * 线程池拒绝 dispatch 时，先把已创建的 Redis turn 标记为失败，再释放栅栏。
     * 这样同一幂等请求重放时不会误看到永久 RUNNING。
     */
    public void compensate(TemporaryTurnAdmission admission, long userId, String errorCode) {
        try {
            turns.fail(admission.turnId(), userId, admission.runId(), admission.runFence(), errorCode);
        } catch (AiApiException expired) {
            if (!"TEMPORARY_SESSION_EXPIRED".equals(expired.code())) throw expired;
            // 临时内容已因删除/过期不可读，但运行栅栏仍要释放，不得阻断下一个任务。
        } finally {
            compensate(userId, admission.runId(), admission.runFence());
        }
    }

    private GuardClaim claimGuard(long userId, AgentRunGuardRecord guard) {
        if (guard.getActiveRunId() != null && "TEMPORARY".equals(guard.getActiveRunType())) {
            // SQL 使用 MySQL CURRENT_TIMESTAMP 精确判定过期，不依赖应用节点时钟。
            if (mapper.releaseExpiredTemporaryGuard(userId, guard.getLockVersion()) != 1) {
                throw AiApiException.activeTurnExists();
            }
            guard = mapper.selectGuardForUpdate(userId);
        }
        if (guard.getActiveRunId() != null) throw AiApiException.activeTurnExists();
        UUID runId = UUID.randomUUID();
        long fence = Math.addExact(guard.getRunFence(), 1);
        if (mapper.claimGuard(userId, runId, "TEMPORARY", fence, LEASE_SECONDS,
                guard.getLockVersion()) != 1) {
            throw AiApiException.activeTurnExists();
        }
        return new GuardClaim(runId, fence);
    }

    private static TemporaryTurnAdmission admission(TemporaryTurnRecord turn, boolean created) {
        return new TemporaryTurnAdmission(turn.turnId(), turn.sessionId(), turn.runId(),
                turn.runFence(), created, turn.state(), turn.webSearchEnabled());
    }

    private record GuardClaim(UUID runId, long fence) {
    }
}
