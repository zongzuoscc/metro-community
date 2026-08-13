package cumt.zongzuo.community.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnRunner;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnEventStore;
import cumt.zongzuo.community.ai.agent.temporary.TemporarySessionService;
import cumt.zongzuo.community.ai.agent.temporary.TemporarySessionStore;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnAdmission;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnLifecycleService;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnRecord;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnRunner;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@TestPropertySource(properties = {
        "metro.ai.enabled=true",
        "metro.ai.agent.enabled=true",
        "metro.ai.memory.enabled=true",
        "metro.ai.embedding.enabled=false"
})
class TemporarySessionIntegrationTest extends IntegrationTestSupport {

    private static final long OWNER = 8_730_001L;

    @Autowired ObjectMapper objectMapper;
    @Autowired StringRedisTemplate redis;
    @Autowired AgentTurnEventStore events;
    @Autowired TemporaryTurnLifecycleService lifecycle;
    @Autowired TemporarySessionService temporarySessions;
    @MockitoSpyBean TemporarySessionStore temporarySessionStore;
    @MockitoSpyBean TemporaryTurnStore temporaryTurns;
    @MockitoBean AgentTurnRunner runner;
    @MockitoBean TemporaryTurnRunner temporaryRunner;

    @BeforeEach
    void seedOwner() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status,deleted)
                VALUES (?,'temporary-owner','encoded','temporary-owner@example.test',0,0,0)
                """, OWNER);
    }

    @AfterEach
    void cleanup() {
        // 该测试套件独占 Testcontainers Redis，因此这里清理临时命名空间，同时覆盖不含 userId 的负数 turn key。
        Set<String> keys = redis.keys("agent:temporary:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        redis.delete("agent:run:user:" + OWNER);
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='AGENT_TURN'");
        jdbcTemplate.update("UPDATE agent_conversation SET last_message_id=NULL WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM agent_message WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM agent_turn WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM agent_episode WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM agent_conversation WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM agent_profile WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM agent_run_guard WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM sys_user WHERE id=?", OWNER);
    }

    @Test
    void temporaryTurnUsesOnlyRedisAndNeverCreatesPersistentConversationRows() throws Exception {
        ResponseEntity<String> session = restTemplate.postForEntity(
                url("/api/agent/temporary-sessions"), new HttpEntity<>(jsonHeaders()), String.class);
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode sessionJson = objectMapper.readTree(session.getBody());
        String sessionId = sessionJson.path("sessionId").asText();
        assertThat(sessionId).isNotBlank();

        ResponseEntity<String> turn = restTemplate.postForEntity(url("/api/agent/turns"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000008731",
                         "message":"这次聊天不要记住我喜欢红色",
                         "temporary":true,"temporarySessionId":"%s","context":{}}
                        """.formatted(sessionId), jsonHeaders()), String.class);

        assertThat(turn.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(turn.getBody()).contains("\"temporary\":true", sessionId);
        long turnId = objectMapper.readTree(turn.getBody()).path("turnId").asLong();
        ResponseEntity<String> snapshot = restTemplate.exchange(url("/api/agent/turns/" + turnId),
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
        assertThat(snapshot.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(snapshot.getBody()).contains("\"temporary\":true",
                "这次聊天不要记住我喜欢红色");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active_run_type FROM agent_run_guard WHERE user_id=?",
                String.class, OWNER)).isEqualTo("TEMPORARY");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_conversation WHERE user_id=?", Integer.class, OWNER)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_turn WHERE user_id=?", Integer.class, OWNER)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_message WHERE user_id=?", Integer.class, OWNER)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_memory_item WHERE user_id=?", Integer.class, OWNER)).isZero();
        verify(temporaryRunner).submit(any(), org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq("这次聊天不要记住我喜欢红色"));
    }

    @Test
    void sessionIsIdempotentAndCannotBeDeletedWhileItsTurnIsActive() throws Exception {
        ResponseEntity<String> first = restTemplate.postForEntity(
                url("/api/agent/temporary-sessions"), new HttpEntity<>(jsonHeaders()), String.class);
        ResponseEntity<String> second = restTemplate.postForEntity(
                url("/api/agent/temporary-sessions"), new HttpEntity<>(jsonHeaders()), String.class);
        String sessionId = objectMapper.readTree(first.getBody()).path("sessionId").asText();
        assertThat(objectMapper.readTree(second.getBody()).path("sessionId").asText())
                .isEqualTo(sessionId);
        assertThat(objectMapper.readTree(second.getBody()).path("expiresAt").asText())
                .isEqualTo(objectMapper.readTree(first.getBody()).path("expiresAt").asText());

        restTemplate.postForEntity(url("/api/agent/turns"), new HttpEntity<>("""
                {"clientRequestId":"00000000-0000-0000-0000-000000008732",
                 "message":"temporary active","temporary":true,
                 "temporarySessionId":"%s","context":{}}
                """.formatted(sessionId), jsonHeaders()), String.class);

        ResponseEntity<String> blocked = restTemplate.exchange(
                url("/api/agent/temporary-sessions"), org.springframework.http.HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders()), String.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody()).contains("ACTIVE_TURN_EXISTS");
    }

    @Test
    void deletingAnIdleSessionMakesItsIdentifierExpired() throws Exception {
        ResponseEntity<String> created = restTemplate.postForEntity(
                url("/api/agent/temporary-sessions"), new HttpEntity<>(jsonHeaders()), String.class);
        String sessionId = objectMapper.readTree(created.getBody()).path("sessionId").asText();

        ResponseEntity<Void> deleted = restTemplate.exchange(
                url("/api/agent/temporary-sessions"), org.springframework.http.HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders()), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> expired = restTemplate.postForEntity(url("/api/agent/turns"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000008733",
                         "message":"expired","temporary":true,
                         "temporarySessionId":"%s","context":{}}
                        """.formatted(sessionId), jsonHeaders()), String.class);
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(expired.getBody()).contains("TEMPORARY_SESSION_EXPIRED");
    }

    @Test
    void temporaryAndPersistentTurnsShareOnePerUserRunGuard() throws Exception {
        ResponseEntity<String> created = restTemplate.postForEntity(
                url("/api/agent/temporary-sessions"), new HttpEntity<>(jsonHeaders()), String.class);
        String sessionId = objectMapper.readTree(created.getBody()).path("sessionId").asText();
        restTemplate.postForEntity(url("/api/agent/turns"), new HttpEntity<>("""
                {"clientRequestId":"00000000-0000-0000-0000-000000008735",
                 "message":"temporary first","temporary":true,
                 "temporarySessionId":"%s","context":{}}
                """.formatted(sessionId), jsonHeaders()), String.class);

        ResponseEntity<String> persistent = restTemplate.postForEntity(url("/api/agent/turns"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000008736",
                         "message":"persistent overlap","temporary":false,"context":{}}
                        """, jsonHeaders()), String.class);

        assertThat(persistent.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(persistent.getBody()).contains("ACTIVE_TURN_EXISTS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_turn WHERE user_id=?", Integer.class, OWNER)).isZero();
    }

    @Test
    void anExpiredTemporaryGuardCannotBlockTheUsersNextPersistentTurnForever() {
        jdbcTemplate.update("""
                INSERT INTO agent_run_guard(user_id,active_run_id,active_run_type,run_fence,
                    lease_until,lock_version,updated_at)
                VALUES (?,UUID_TO_BIN('00000000-0000-0000-0000-000000008737'),
                    'TEMPORARY',7,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),1,
                    CURRENT_TIMESTAMP(6))
                """, OWNER);

        ResponseEntity<String> next = restTemplate.postForEntity(url("/api/agent/turns"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000008738",
                         "message":"recover after crash","temporary":false,"context":{}}
                        """, jsonHeaders()), String.class);

        assertThat(next.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active_run_type FROM agent_run_guard WHERE user_id=?",
                String.class, OWNER)).isEqualTo("PERSISTENT");
    }

    @Test
    void temporaryEventStreamNeverOutlivesTheAbsoluteSessionDeadline() throws Exception {
        ResponseEntity<String> created = restTemplate.postForEntity(
                url("/api/agent/temporary-sessions"), new HttpEntity<>(jsonHeaders()), String.class);
        JsonNode session = objectMapper.readTree(created.getBody());
        String sessionId = session.path("sessionId").asText();
        java.time.Instant shortExpiry = java.time.Instant.now().plusSeconds(8);
        redis.opsForValue().set("agent:temporary:session:" + OWNER, objectMapper.writeValueAsString(
                        java.util.Map.of("sessionId", sessionId,
                                "createdAt", session.path("createdAt").asText(),
                                "expiresAt", shortExpiry.toString())),
                java.time.Duration.ofSeconds(8));

        ResponseEntity<String> accepted = restTemplate.postForEntity(url("/api/agent/turns"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000008739",
                         "message":"short lived","temporary":true,
                         "temporarySessionId":"%s","context":{}}
                        """.formatted(sessionId), jsonHeaders()), String.class);
        long turnId = objectMapper.readTree(accepted.getBody()).path("turnId").asLong();
        Long eventTtl = redis.getExpire("agent:turn:" + turnId + ":events",
                java.util.concurrent.TimeUnit.SECONDS);

        assertThat(eventTtl).isNotNull().isBetween(1L, 8L);
    }

    @Test
    void deletingSessionDuringTerminalEventAppendCannotRecreateAnswerContent() throws Exception {
        ResponseEntity<String> created = restTemplate.postForEntity(
                url("/api/agent/temporary-sessions"), new HttpEntity<>(jsonHeaders()), String.class);
        String sessionId = objectMapper.readTree(created.getBody()).path("sessionId").asText();
        ResponseEntity<String> accepted = restTemplate.postForEntity(url("/api/agent/turns"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000008740",
                         "message":"delete race","temporary":true,
                         "temporarySessionId":"%s","context":{}}
                        """.formatted(sessionId), jsonHeaders()), String.class);
        long turnId = objectMapper.readTree(accepted.getBody()).path("turnId").asLong();
        TemporaryTurnRecord turn = temporaryTurns.find(turnId, OWNER);
        TemporaryTurnAdmission admission = new TemporaryTurnAdmission(turnId, turn.sessionId(),
                turn.runId(), turn.runFence(), true, turn.state());
        assertThat(lifecycle.complete(admission, OWNER, new GroundedAgentAnswer(
                "删除后绝对不得复活的最终回答", java.util.List.of(), "stop"))).isTrue();

        CountDownLatch appendReady = new CountDownLatch(1);
        CountDownLatch deleteReady = new CountDownLatch(1);
        CountDownLatch allowAppend = new CountDownLatch(1);
        CountDownLatch allowDelete = new CountDownLatch(1);
        doAnswer(invocation -> {
            String value = (String) invocation.callRealMethod();
            // 这个 latch 固定在“已校验 turn/session、即将原子 XADD”的最窄竞态窗口。
            appendReady.countDown();
            assertThat(allowAppend.await(10, TimeUnit.SECONDS)).isTrue();
            return value;
        }).when(temporaryTurns).sessionKey(any());
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            // 父 session 已失效、子键尚未清理时停住，专门验证迟到 append 不能越过删除墓碑。
            deleteReady.countDown();
            assertThat(allowDelete.await(10, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(temporarySessionStore).invalidate(anyLong(), any());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var append = executor.submit(() -> events.append(turnId, OWNER, turn.runId(),
                    turn.runFence(), "done", Map.of(
                            "finalMessage", "删除后绝对不得复活的最终回答",
                            "citationCount", 0)));
            assertThat(appendReady.await(10, TimeUnit.SECONDS)).isTrue();
            var delete = executor.submit(() -> temporarySessions.delete(OWNER));
            assertThat(deleteReady.await(10, TimeUnit.SECONDS)).isTrue();
            allowAppend.countDown();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> append.get(10, TimeUnit.SECONDS)).isInstanceOf(Exception.class);
            allowDelete.countDown();
            delete.get(10, TimeUnit.SECONDS);
        }

        assertThat(redis.hasKey("agent:turn:" + turnId + ":events")).isFalse();
        assertThat(redis.hasKey("agent:temporary:session:" + OWNER)).isFalse();
        assertThat(redis.hasKey("agent:temporary:turn:" + turnId)).isFalse();
    }

    @Test
    void concurrentIdenticalRequestsReturnOneTemporaryTurnInsteadOfAConflict() throws Exception {
        ResponseEntity<String> created = restTemplate.postForEntity(
                url("/api/agent/temporary-sessions"), new HttpEntity<>(jsonHeaders()), String.class);
        String sessionId = objectMapper.readTree(created.getBody()).path("sessionId").asText();
        String body = """
                {"clientRequestId":"00000000-0000-0000-0000-000000008741",
                 "message":"same request","temporary":true,
                 "temporarySessionId":"%s","context":{}}
                """.formatted(sessionId);

        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch secondRead = new CountDownLatch(1);
        CountDownLatch allowCreate = new CountDownLatch(1);
        AtomicBoolean firstCreate = new AtomicBoolean(true);
        AtomicInteger requestReads = new AtomicInteger();
        doAnswer(invocation -> {
            Object value = invocation.callRealMethod();
            // 第 1 次是首请求快速读，第 2 次是其行锁内复查，第 3 次才是并发重放的快速读。
            if (requestReads.incrementAndGet() == 3) secondRead.countDown();
            return value;
        }).when(temporaryTurns).findByRequest(anyLong(), any(), any());
        doAnswer(invocation -> {
            if (firstCreate.compareAndSet(true, false)) {
                createEntered.countDown();
                assertThat(allowCreate.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(temporaryTurns).create(anyLong(), anyLong(), any(), any(), any(), anyLong(), any(),
                any(), org.mockito.ArgumentMatchers.anyBoolean());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> restTemplate.postForEntity(url("/api/agent/turns"),
                    new HttpEntity<>(body, jsonHeaders()), String.class));
            assertThat(createEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> restTemplate.postForEntity(url("/api/agent/turns"),
                    new HttpEntity<>(body, jsonHeaders()), String.class));
            assertThat(secondRead.await(10, TimeUnit.SECONDS)).isTrue();
            allowCreate.countDown();

            ResponseEntity<String> firstResponse = first.get(10, TimeUnit.SECONDS);
            ResponseEntity<String> secondResponse = second.get(10, TimeUnit.SECONDS);
            assertThat(java.util.List.of(firstResponse.getStatusCode(), secondResponse.getStatusCode()))
                    .containsExactlyInAnyOrder(HttpStatus.ACCEPTED, HttpStatus.OK);
            assertThat(objectMapper.readTree(firstResponse.getBody()).path("turnId").asLong())
                    .isEqualTo(objectMapper.readTree(secondResponse.getBody()).path("turnId").asLong());
        }
    }

    @Test
    void lostLuaResponseTerminatesTheExactlyCreatedTurnBeforeIdempotentReplay() throws Exception {
        ResponseEntity<String> created = restTemplate.postForEntity(
                url("/api/agent/temporary-sessions"), new HttpEntity<>(jsonHeaders()), String.class);
        String sessionId = objectMapper.readTree(created.getBody()).path("sessionId").asText();
        String body = """
                {"clientRequestId":"00000000-0000-0000-0000-000000008742",
                 "message":"ambiguous redis result","temporary":true,
                 "temporarySessionId":"%s","context":{}}
                """.formatted(sessionId);
        AtomicBoolean loseFirstResponse = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object createdTurn = invocation.callRealMethod();
            if (loseFirstResponse.compareAndSet(true, false)) {
                // 模拟 Lua 已在 Redis 服务端完整提交，但客户端在收到返回包前断连。
                throw new org.springframework.data.redis.RedisConnectionFailureException(
                        "simulated lost Lua response");
            }
            return createdTurn;
        }).when(temporaryTurns).create(anyLong(), anyLong(), any(), any(), any(), anyLong(), any(),
                any(), org.mockito.ArgumentMatchers.anyBoolean());

        ResponseEntity<String> uncertain = restTemplate.postForEntity(url("/api/agent/turns"),
                new HttpEntity<>(body, jsonHeaders()), String.class);
        assertThat(uncertain.getStatusCode().is5xxServerError()).isTrue();

        ResponseEntity<String> replay = restTemplate.postForEntity(url("/api/agent/turns"),
                new HttpEntity<>(body, jsonHeaders()), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(replay.getBody()).path("state").asText()).isEqualTo("FAILED");
        // 首次为该用户创建栕栏时，整个 MySQL 事务会随 Redis 异常回滚，因此合法结果可以是
        // “栕栏行不存在”或“栕栏行存在但已释放”；唯一不允许的是残留 active_run_id。
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_run_guard
                WHERE user_id=? AND active_run_id IS NOT NULL
                """, Integer.class, OWNER)).isZero();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(OWNER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
