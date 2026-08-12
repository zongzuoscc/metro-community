package cumt.zongzuo.community.ai.agent.turn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cumt.zongzuo.community.ai.web.AiApiException;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.outbox.DomainEventOutboxService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 为持久 Agent turn 建立会话事实、幂等记录和用户级运行栅栏。
 *
 * <p>MySQL 保存可恢复的业务事实，Redis 租约只负责运行期心跳。两者必须共用 runId/runFence，
 * 且 Redis 申请失败时要终结 turn 并释放 MySQL guard，否则用户会被永久占用。</p>
 */
@Service
public class AgentTurnAdmissionService {

    private static final Duration LEASE = Duration.ofMinutes(2);

    private final AgentTurnMapper mapper;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transactions;
    private final DomainEventOutboxService outbox;
    private final ObjectMapper objectMapper;
    private final AgentRunLeaseStore leases;

    public AgentTurnAdmissionService(AgentTurnMapper mapper, StringRedisTemplate redis,
                                     PlatformTransactionManager transactionManager,
                                     DomainEventOutboxService outbox, ObjectMapper objectMapper,
                                     AgentRunLeaseStore leases) {
        this.mapper = mapper;
        this.redis = redis;
        this.transactions = new TransactionTemplate(transactionManager);
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.leases = leases;
    }

    /**
     * 幂等接纳一个持久 turn。同一 clientRequestId 且请求哈希一致时返回原 turn；
     * 仅在新 turn 创建成功时申请 Redis 租约。
     */
    public AgentTurnAdmission admit(AgentTurnCreateCommand command) {
        assertRedisAvailable();
        PendingAdmission pending = transactions.execute(status -> admitInTransaction(command));
        if (pending == null) {
            throw new IllegalStateException("Agent admission transaction returned no result");
        }
        if (!pending.admission().created()) {
            return pending.admission();
        }
        boolean claimed;
        try {
            claimed = leases.claim(command.userId(), pending.admission().runId(),
                    pending.admission().runFence());
        } catch (RuntimeException leaseFailure) {
            compensateFailedDispatch(pending.admission(), command.userId(),
                    "REDIS_LEASE_CLAIM_FAILED");
            throw AiApiException.runtimeUnavailable(Duration.ofSeconds(1));
        }
        if (!claimed) {
            compensateFailedDispatch(pending.admission(), command.userId(),
                    "REDIS_LEASE_CLAIM_FAILED");
            throw AiApiException.runtimeUnavailable(Duration.ofSeconds(1));
        }
        return pending.admission();
    }

    /**
     * dispatch 或租约申请失败时，在精确 run fence 下将 turn 收口为 FAILED 并释放 guard。
     * 旧 worker 或重复补偿无法跨过 runId/runFence 修改新任务。
     */
    public void compensateFailedDispatch(AgentTurnAdmission admission, long userId,
                                         String errorCode) {
        transactions.executeWithoutResult(status -> {
            AgentRunGuardRecord guard = mapper.selectGuardForUpdate(userId);
            AgentTurnRecord turn = mapper.selectByIdForUpdate(admission.turnId(), userId);
            if (guard == null || turn == null
                    || !admission.runId().equals(guard.getActiveRunId())
                    || guard.getRunFence() != admission.runFence()
                    || !admission.runId().equals(turn.getRunId())
                    || turn.getRunFence() != admission.runFence()
                    || !"RUNNING".equals(turn.getState())) {
                return;
            }
            if (mapper.failTurn(admission.turnId(), userId, admission.runId(),
                    admission.runFence(), errorCode) != 1
                    || mapper.releaseGuard(userId, admission.runId(),
                    admission.runFence()) != 1) {
                throw new IllegalStateException("Agent failed dispatch compensation lost its fence");
            }
        });
        try {
            leases.release(userId, admission.runId(), admission.runFence());
        } catch (RuntimeException ignored) {
            // MySQL 是最终权威；即使此处 Redis 释放失败，后续更高 fence 也会安全覆盖旧租约。
        }
    }

    private PendingAdmission admitInTransaction(AgentTurnCreateCommand command) {
        mapper.ensureGuard(command.userId());
        mapper.ensureProfile(command.userId());
        mapper.ensureConversation(command.userId());
        long conversationId = required(mapper.selectConversationIdForUpdate(command.userId()),
                "conversation");
        mapper.ensureActiveEpisode(command.userId(), conversationId);
        long episodeId = required(mapper.selectActiveEpisodeIdForUpdate(command.userId(),
                conversationId), "active episode");
        String requestHash = requestHash(command);
        AgentTurnRecord existing = mapper.selectByClientRequest(command.userId(), conversationId,
                command.clientRequestId());
        if (existing != null) {
            if (!requestHash.equals(existing.getRequestHash())) {
                throw AiApiException.idempotencyConflict();
            }
            return new PendingAdmission(new AgentTurnAdmission(existing.getId(), existing.getRunId(),
                    existing.getRunFence(), false, existing.getState()));
        }
        AgentRunGuardRecord guard = mapper.selectGuardForUpdate(command.userId());
        if (guard == null) {
            throw new IllegalStateException("Agent run guard is missing");
        }
        guard = reclaimExpiredTemporaryGuard(command.userId(), guard);
        if (guard.getActiveRunId() != null) {
            throw AiApiException.activeTurnExists();
        }
        UUID runId = UUID.randomUUID();
        long fence = Math.addExact(guard.getRunFence(), 1);
        if (mapper.claimGuard(command.userId(), runId, "PERSISTENT", fence, LEASE.toSeconds(),
                guard.getLockVersion()) != 1) {
            throw AiApiException.activeTurnExists();
        }
        AgentTurnRecord turn = new AgentTurnRecord();
        turn.setUserId(command.userId());
        turn.setConversationId(conversationId);
        turn.setEpisodeId(episodeId);
        turn.setRunId(runId);
        turn.setClientRequestId(command.clientRequestId());
        turn.setRequestHash(requestHash);
        turn.setTaskType(command.taskType());
        turn.setPageContextJson(command.pageContextJson());
        turn.setRunFence(fence);
        mapper.insertTurn(turn, LEASE.toSeconds());
        mapper.insertUserMessage(command.userId(), turn.getId(), conversationId, episodeId,
                command.message(), sha256(command.message()));
        ObjectNode payload = objectMapper.createObjectNode()
                .put("turnId", turn.getId()).put("userId", command.userId())
                .put("conversationId", conversationId).put("runId", runId.toString())
                .put("runFence", fence).put("taskType", command.taskType());
        outbox.append("AGENT_TURN", turn.getId(), fence, 1,
                DomainEventType.AGENT_TURN_REQUESTED, 1, payload,
                "AGENT_TURN:" + turn.getId() + ":1:" + fence + ":AGENT_TURN_REQUESTED");
        return new PendingAdmission(new AgentTurnAdmission(turn.getId(), runId, fence, true,
                "RUNNING"));
    }

    private void assertRedisAvailable() {
        try {
            String pong = redis.getConnectionFactory().getConnection().ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw AiApiException.runtimeUnavailable(Duration.ofSeconds(1));
            }
        } catch (AiApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw AiApiException.runtimeUnavailable(Duration.ofSeconds(1));
        }
    }

    /**
     * 临时 turn 没有 MySQL turn 行可供 recovery 扫描，所以新 admission 会在同一 guard 行锁下回收过期占用。
     */
    private AgentRunGuardRecord reclaimExpiredTemporaryGuard(long userId,
                                                             AgentRunGuardRecord guard) {
        if (guard.getActiveRunId() == null || !"TEMPORARY".equals(guard.getActiveRunType())) {
            return guard;
        }
        // 是否过期由 SQL 使用数据库时间判定，避免应用节点与 MySQL 时钟偏差导致误回收。
        if (mapper.releaseExpiredTemporaryGuard(userId, guard.getLockVersion()) != 1) {
            throw AiApiException.activeTurnExists();
        }
        return mapper.selectGuardForUpdate(userId);
    }

    private static String requestHash(AgentTurnCreateCommand command) {
        return sha256(command.message() + "\n" + command.pageContextJson() + "\n"
                + command.taskType());
    }

    /**
     * 计算持久 turn 与临时 turn 共用的稳定 SHA-256 哈希。
     *
     * <p>该值用来区分“同一 clientRequestId 的安全重放”与“相同 ID 但请求内容已变更”。</p>
     */
    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static long required(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalStateException("Agent " + name + " is missing");
        }
        return value;
    }

    private record PendingAdmission(AgentTurnAdmission admission) {
    }
}
