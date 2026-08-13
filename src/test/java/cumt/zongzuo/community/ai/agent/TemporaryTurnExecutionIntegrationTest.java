package cumt.zongzuo.community.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通过真实 HTTP、MySQL 与 Redis 执行临时 runner，测试中仅替换面向模型的回答服务。 */
@TestPropertySource(properties = {
        "metro.ai.enabled=true",
        "metro.ai.agent.enabled=true",
        "metro.ai.memory.enabled=true",
        "metro.ai.embedding.enabled=false"
})
class TemporaryTurnExecutionIntegrationTest extends IntegrationTestSupport {

    private static final long OWNER = 8_730_002L;

    @Autowired ObjectMapper objectMapper;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @MockitoBean GroundedAnswerService answers;

    @BeforeEach
    void seedOwner() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status,deleted)
                VALUES (?,'temporary-runner-owner','encoded','temporary-runner@example.test',0,0,0)
                """, OWNER);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys("agent:temporary:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        redis.delete("agent:run:user:" + OWNER);
        jdbcTemplate.update("DELETE FROM agent_memory_item WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM agent_message WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM agent_turn WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM agent_episode WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM agent_conversation WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM agent_profile WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM agent_run_guard WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM sys_user WHERE id=?", OWNER);
    }

    @Test
    void realRunnerCompletesWithoutDurableContentAndReleasesTheSharedGuard() throws Exception {
        when(answers.answerTemporary(eq(OWNER), any(), eq("do not remember"), eq(List.of()),
                eq(true), any()))
                .thenReturn(new GroundedAgentAnswer("temporary answer", List.of(), "stop"));
        ResponseEntity<String> session = restTemplate.postForEntity(
                url("/api/agent/temporary-sessions"), new HttpEntity<>(headers()), String.class);
        String sessionId = objectMapper.readTree(session.getBody()).path("sessionId").asText();

        ResponseEntity<String> accepted = restTemplate.postForEntity(url("/api/agent/turns"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000008734",
                         "message":"do not remember","temporary":true,
                         "temporarySessionId":"%s","context":{}}
                        """.formatted(sessionId), headers()), String.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long turnId = objectMapper.readTree(accepted.getBody()).path("turnId").asLong();

        JsonNode snapshot = awaitSucceeded(turnId);
        assertThat(snapshot.path("temporary").asBoolean()).isTrue();
        assertThat(snapshot.path("finalMessage").asText()).isEqualTo("temporary answer");
        // Redis 的终态快照会先于 MySQL 事务提交对轮询线程可见，因此必须等待共享 guard 完成提交，
        // 不能把正常的跨存储可见性窗口误判成租约泄漏。
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM agent_run_guard
                        WHERE user_id=? AND active_run_id IS NOT NULL
                        """, Integer.class, OWNER)).isZero());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_turn WHERE user_id=?", Integer.class, OWNER)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_message WHERE user_id=?", Integer.class, OWNER)).isZero();
        verify(answers).answerTemporary(eq(OWNER), any(), eq("do not remember"),
                eq(List.of()), eq(true), any());
    }

    private JsonNode awaitSucceeded(long turnId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        JsonNode latest = null;
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<String> response = restTemplate.exchange(url("/api/agent/turns/" + turnId),
                    org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers()), String.class);
            latest = objectMapper.readTree(response.getBody());
            if ("SUCCEEDED".equals(latest.path("state").asText())) return latest;
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        throw new AssertionError("Temporary turn did not succeed: " + latest);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(OWNER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
