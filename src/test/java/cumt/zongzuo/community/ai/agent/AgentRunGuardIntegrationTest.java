package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnAdmission;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnAdmissionService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnCreateCommand;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnFinalizer;
import cumt.zongzuo.community.ai.agent.turn.AgentRunLeaseStore;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnRunner;
import cumt.zongzuo.community.ai.web.AiApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@TestPropertySource(properties = {
        "metro.ai.enabled=true",
        "metro.ai.agent.enabled=true",
        "metro.ai.embedding.enabled=false"
})
class AgentRunGuardIntegrationTest extends IntegrationTestSupport {

    private static final long USER_ID = 8_710_001L;

    @Autowired
    private AgentTurnAdmissionService admissions;

    @Autowired
    private AgentTurnFinalizer finalizer;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoSpyBean
    private AgentRunLeaseStore leases;

    @MockitoBean
    private AgentTurnRunner runner;

    @BeforeEach
    void seedUser() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status,deleted)
                VALUES (?,?,'encoded','agent-run-guard@example.test',0,0,0)
                """, USER_ID, "agent-run-guard");
    }

    @AfterEach
    void cleanup() {
        reset(leases);
        redis.delete("agent:run:user:" + USER_ID);
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='AGENT_TURN'");
        jdbcTemplate.update("DELETE FROM agent_answer_citation WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_retrieval_hit WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_tool_call WHERE user_id=?", USER_ID);
        jdbcTemplate.update("UPDATE agent_conversation SET last_message_id=NULL WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_message WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_turn WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_episode WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_conversation WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_profile WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_run_guard WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM sys_user WHERE id=?", USER_ID);
    }

    @Test
    void redisClaimFailureTerminalizesTheTurnAndReleasesTheUserGuard() {
        doReturn(false).when(leases).claim(anyLong(), any(UUID.class), anyLong());

        assertThatThrownBy(() -> admissions.admit(command(UUID.randomUUID(), "claim failure")))
                .isInstanceOfSatisfying(AiApiException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT_RUNTIME_UNAVAILABLE"));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT active_run_id IS NULL FROM agent_run_guard WHERE user_id=?
                """, Boolean.class, USER_ID)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM agent_turn WHERE user_id=? ORDER BY id DESC LIMIT 1
                """, String.class, USER_ID)).isEqualTo("FAILED");
    }

    @Test
    void redisClaimExceptionTerminalizesTheTurnAndReleasesTheUserGuard() {
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"))
                .when(leases).claim(anyLong(), any(UUID.class), anyLong());

        assertThatThrownBy(() -> admissions.admit(command(UUID.randomUUID(), "claim exception")))
                .isInstanceOfSatisfying(AiApiException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT_RUNTIME_UNAVAILABLE"));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT active_run_id IS NULL FROM agent_run_guard WHERE user_id=?
                """, Boolean.class, USER_ID)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM agent_turn WHERE user_id=? ORDER BY id DESC LIMIT 1
                """, String.class, USER_ID)).isEqualTo("FAILED");
    }

    @Test
    void sameRequestIsIdempotentButAnotherActiveTurnIsRejected() {
        UUID clientRequestId = UUID.randomUUID();
        AgentTurnCreateCommand command = command(clientRequestId, "How do row locks work?");

        AgentTurnAdmission first = admissions.admit(command);
        AgentTurnAdmission replay = admissions.admit(command);

        assertThat(replay.turnId()).isEqualTo(first.turnId());
        assertThat(replay.runId()).isEqualTo(first.runId());
        assertThat(replay.runFence()).isEqualTo(1);
        assertThat(replay.created()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_turn WHERE user_id=?", Integer.class, USER_ID)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_message WHERE user_id=? AND role='USER'",
                Integer.class, USER_ID)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_type='AGENT_TURN' AND aggregate_id=?
                  AND event_type='AGENT_TURN_REQUESTED' AND state='PENDING'
                """, Integer.class, first.turnId())).isOne();

        assertThatThrownBy(() -> admissions.admit(command(UUID.randomUUID(), "second request")))
                .isInstanceOfSatisfying(AiApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ACTIVE_TURN_EXISTS"));
        assertThatThrownBy(() -> admissions.admit(command(clientRequestId, "changed body")))
                .isInstanceOfSatisfying(AiApiException.class,
                        error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void completedRunAdvancesFenceAndRejectsTheOldWorkersLateResult() {
        AgentTurnAdmission first = admissions.admit(command(UUID.randomUUID(), "first"));
        assertThat(finalizer.complete(first.turnId(), first.runId(), first.runFence(),
                new GroundedAgentAnswer("Grounded answer.", java.util.List.of(), "stop"))).isTrue();

        AgentTurnAdmission second = admissions.admit(command(UUID.randomUUID(), "second"));

        assertThat(second.runFence()).isEqualTo(2);
        assertThat(finalizer.complete(first.turnId(), first.runId(), first.runFence(),
                new GroundedAgentAnswer("late", java.util.List.of(), "stop"))).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_message
                WHERE turn_id=? AND role='ASSISTANT'
                """, Integer.class, first.turnId())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT active_run_id IS NOT NULL FROM agent_run_guard WHERE user_id=?
                """, Boolean.class, USER_ID)).isTrue();
    }

    @Test
    void redisLeaseAcceptsOnlyTheNewestFenceAndExactOwnerCanRenewOrRelease() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(leases.claim(USER_ID, first, 1)).isTrue();
        assertThat(leases.renew(USER_ID, first, 1)).isTrue();
        assertThat(leases.claim(USER_ID, second, 2)).isTrue();
        assertThat(leases.renew(USER_ID, first, 1)).isFalse();
        assertThat(leases.release(USER_ID, first, 1)).isFalse();
        assertThat(leases.release(USER_ID, second, 2)).isTrue();
    }

    private static AgentTurnCreateCommand command(UUID clientRequestId, String message) {
        return new AgentTurnCreateCommand(USER_ID, clientRequestId, message,
                "{\"route\":\"COMMUNITY_HOME\"}", "COMMUNITY_QA");
    }
}
