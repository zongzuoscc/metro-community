package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnAdmissionService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnCreateCommand;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnFinalizer;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnRecovery;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnRunner;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnLeaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@TestPropertySource(properties = {"metro.ai.enabled=true", "metro.ai.agent.enabled=true"})
class AgentTurnRecoveryIntegrationTest extends IntegrationTestSupport {

    private static final long USER_ID = 8_730_001L;

    @Autowired private AgentTurnAdmissionService admissions;
    @Autowired private AgentTurnRecovery recovery;
    @Autowired private AgentTurnFinalizer finalizer;
    @Autowired private StringRedisTemplate redis;
    @Autowired private AgentTurnLeaseService turnLeases;
    @MockitoBean private AgentTurnRunner runner;

    @BeforeEach
    void seed() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status,deleted)
                VALUES (?,'agent-recovery','encoded','agent-recovery@example.test',0,0,0)
                """, USER_ID);
    }

    @AfterEach
    void cleanup() {
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
    void expiredTurnGetsANewRunFenceAndTheOldWorkerCannotComplete() {
        var old = admissions.admit(new AgentTurnCreateCommand(USER_ID, UUID.randomUUID(),
                "recover this", "{}", "COMMUNITY_QA"));
        jdbcTemplate.update("UPDATE agent_turn SET lease_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",
                old.turnId());
        jdbcTemplate.update("UPDATE agent_run_guard SET lease_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE user_id=?",
                USER_ID);
        redis.delete("agent:run:user:" + USER_ID);

        var recovered = recovery.recoverOne();

        assertThat(recovered).isPresent();
        assertThat(recovered.orElseThrow().turnId()).isEqualTo(old.turnId());
        assertThat(recovered.orElseThrow().runFence()).isEqualTo(2);
        assertThat(recovered.orElseThrow().runId()).isNotEqualTo(old.runId());
        assertThat(finalizer.complete(old.turnId(), old.runId(), old.runFence(),
                new GroundedAgentAnswer("late", List.of(), "stop"))).isFalse();
        assertThat(turnLeases.renew(old.turnId(), USER_ID, old.runId(), old.runFence())).isFalse();
        assertThat(turnLeases.renew(recovered.orElseThrow().turnId(), USER_ID,
                recovered.orElseThrow().runId(), recovered.orElseThrow().runFence())).isTrue();
        verify(runner).submit(eq(recovered.orElseThrow()), eq(USER_ID), eq("recover this"));
    }
}
