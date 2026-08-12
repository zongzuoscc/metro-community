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
            // MySQL is authoritative. A later, higher fence safely replaces a stale Redis lease.
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
        if (guard.getActiveRunId() != null) {
            throw AiApiException.activeTurnExists();
        }
        UUID runId = UUID.randomUUID();
        long fence = Math.addExact(guard.getRunFence(), 1);
        if (mapper.claimGuard(command.userId(), runId, fence, LEASE.toSeconds(),
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

    private static String requestHash(AgentTurnCreateCommand command) {
        return sha256(command.message() + "\n" + command.pageContextJson() + "\n"
                + command.taskType());
    }

    static String sha256(String value) {
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
