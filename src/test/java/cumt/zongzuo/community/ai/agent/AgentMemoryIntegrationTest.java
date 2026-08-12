package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryCaptureService;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryRecallService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnAdmission;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnAdmissionService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnCreateCommand;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "metro.ai.enabled=true",
        "metro.ai.agent.enabled=true",
        "metro.ai.memory.enabled=true",
        "metro.ai.embedding.enabled=false"
})
class AgentMemoryIntegrationTest extends IntegrationTestSupport {

    private static final long OWNER = 98_001L;
    private static final long OTHER = 98_002L;

    @Autowired AgentTurnAdmissionService admissions;
    @Autowired AgentMemoryCaptureService capture;
    @Autowired AgentMemoryRecallService recall;
    @Autowired StringRedisTemplate redis;
    @MockitoBean AgentTurnRunner runner;

    @BeforeEach
    void seedUsers() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status,deleted)
                VALUES (?,?,'encoded',?,0,0,0),(?,?,'encoded',?,0,0,0)
                """, OWNER, "memory-owner", "memory-owner@example.test",
                OTHER, "memory-other", "memory-other@example.test");
    }

    @AfterEach
    void cleanup() {
        for (long userId : new long[]{OWNER, OTHER}) {
            redis.delete("agent:run:user:" + userId);
            jdbcTemplate.update("DELETE FROM agent_memory_projection WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_memory_source WHERE user_id=?", userId);
            jdbcTemplate.update("UPDATE agent_memory_item SET current_version_id=NULL WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_memory_version WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_memory_item WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_memory_setting WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='AGENT_TURN'");
            jdbcTemplate.update("UPDATE agent_conversation SET last_message_id=NULL WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_message WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_turn WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_episode WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_conversation WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_profile WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_run_guard WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id=?", userId);
        }
    }

    @Test
    void explicitLowRiskFactIsSavedWithoutConfirmationAndRecalledAcrossTurns() {
        AgentTurnAdmission first = admit(OWNER, "我喜欢简洁的回答风格");

        assertThat(capture.captureUserMessage(OWNER, first.turnId())).isOne();
        assertThat(capture.captureUserMessage(OWNER, first.turnId())).isZero();

        var memories = recall.recall(OWNER, "你记得我喜欢什么样的回答吗？", 8);
        assertThat(memories).singleElement().satisfies(memory -> {
            assertThat(memory.category()).isEqualTo("PREFERENCE");
            assertThat(memory.content()).isEqualTo("我喜欢简洁的回答风格");
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM agent_memory_item WHERE user_id=?", String.class, OWNER))
                .isEqualTo("ACTIVE");
    }

    @Test
    void credentialsAndSensitiveFactsAreNeverSaved() {
        AgentTurnAdmission password = admit(OWNER, "请记住我的密码是 hunter2");
        assertThat(capture.captureUserMessage(OWNER, password.turnId())).isZero();
        jdbcTemplate.update("UPDATE agent_run_guard SET active_run_id=NULL,active_run_type=NULL,lease_until=NULL WHERE user_id=?", OWNER);
        AgentTurnAdmission health = admit(OWNER, "我患有糖尿病，请记住");
        assertThat(capture.captureUserMessage(OWNER, health.turnId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_memory_item WHERE user_id=?", Integer.class, OWNER)).isZero();
    }

    @Test
    void ownerCanListEditDeleteAndDisableAutomaticMemory() {
        AgentTurnAdmission turn = admit(OWNER, "我的目标是学会 MySQL 事务");
        capture.captureUserMessage(OWNER, turn.turnId());
        long memoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_memory_item WHERE user_id=?", Long.class, OWNER);

        ResponseEntity<String> listed = exchange(HttpMethod.GET, "/api/agent/memories", null, OWNER);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).contains("我的目标是学会 MySQL 事务", "GOAL");
        assertThat(exchange(HttpMethod.GET, "/api/agent/memories/" + memoryId, null, OTHER)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> edited = exchange(HttpMethod.PUT, "/api/agent/memories/" + memoryId,
                "{\"content\":\"我的目标是掌握 MySQL 事务和锁\",\"expectedVersion\":1}", OWNER);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody()).contains("掌握 MySQL 事务和锁", "\"version\":\"2\"");
        assertThat(jdbcTemplate.queryForList("""
                SELECT p.state FROM agent_memory_projection p
                JOIN agent_memory_version v ON v.id=p.memory_version_id
                WHERE v.memory_id=? ORDER BY v.version_no
                """, String.class, memoryId)).containsExactly("DELETING", "PENDING");

        assertThat(exchange(HttpMethod.PUT, "/api/agent/memory-settings",
                "{\"enabled\":false,\"expectedVersion\":0}", OWNER).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbcTemplate.update("UPDATE agent_run_guard SET active_run_id=NULL,active_run_type=NULL,lease_until=NULL WHERE user_id=?", OWNER);
        AgentTurnAdmission ignored = admit(OWNER, "我喜欢详细的回答");
        assertThat(capture.captureUserMessage(OWNER, ignored.turnId())).isZero();

        assertThat(exchange(HttpMethod.DELETE, "/api/agent/memories/" + memoryId, null, OWNER)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(recall.recall(OWNER, "MySQL", 8)).isEmpty();
        assertThat(jdbcTemplate.queryForList("""
                SELECT p.state FROM agent_memory_projection p
                JOIN agent_memory_version v ON v.id=p.memory_version_id
                WHERE v.memory_id=? ORDER BY v.version_no
                """, String.class, memoryId)).containsOnly("DELETING");
    }

    @Test
    void manualEditCannotTurnALowRiskMemoryIntoSensitiveData() {
        AgentTurnAdmission turn = admit(OWNER, "我喜欢简洁的回答");
        capture.captureUserMessage(OWNER, turn.turnId());
        long memoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_memory_item WHERE user_id=?", Long.class, OWNER);

        ResponseEntity<String> rejected = exchange(HttpMethod.PUT,
                "/api/agent/memories/" + memoryId,
                "{\"content\":\"我的 API key 是 secret-123\",\"expectedVersion\":1}", OWNER);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).contains("VALIDATION_FAILED");
        assertThat(recall.find(OWNER, memoryId).content()).isEqualTo("我喜欢简洁的回答");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_memory_version WHERE memory_id=?",
                Integer.class, memoryId)).isOne();
    }

    private AgentTurnAdmission admit(long userId, String message) {
        return admissions.admit(new AgentTurnCreateCommand(userId, UUID.randomUUID(), message,
                "{}", "COMMUNITY_QA"));
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String body, long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(userId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url(path), method,
                new HttpEntity<>(body, headers), String.class);
    }
}
