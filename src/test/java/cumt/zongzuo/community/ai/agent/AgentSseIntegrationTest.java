package cumt.zongzuo.community.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnAdmissionService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnEventStore;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnRunner;
import cumt.zongzuo.community.ai.web.AiApiException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

@TestPropertySource(properties = {
        "metro.ai.enabled=true",
        "metro.ai.agent.enabled=true",
        "metro.ai.embedding.enabled=false"
})
class AgentSseIntegrationTest extends IntegrationTestSupport {

    private static final long OWNER = 8_720_001L;
    private static final long OTHER = 8_720_002L;

    @Autowired
    private AgentTurnEventStore events;

    @Autowired
    private AgentTurnAdmissionService admissions;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoBean
    private AgentTurnRunner runner;

    @BeforeEach
    void seedUsers() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status,deleted) VALUES
                (?,'agent-sse-owner','encoded','agent-sse-owner@example.test',0,0,0),
                (?,'agent-sse-other','encoded','agent-sse-other@example.test',0,0,0)
                """, OWNER, OTHER);
    }

    @AfterEach
    void cleanup() {
        redis.delete(java.util.List.of("agent:run:user:" + OWNER, "agent:run:user:" + OTHER));
        jdbcTemplate.update("DELETE FROM agent_answer_citation WHERE user_id IN (?,?)", OWNER, OTHER);
        jdbcTemplate.update("DELETE FROM agent_retrieval_hit WHERE user_id IN (?,?)", OWNER, OTHER);
        jdbcTemplate.update("DELETE FROM agent_tool_call WHERE user_id IN (?,?)", OWNER, OTHER);
        jdbcTemplate.update("UPDATE agent_conversation SET last_message_id=NULL WHERE user_id IN (?,?)",
                OWNER, OTHER);
        jdbcTemplate.update("DELETE FROM agent_message WHERE user_id IN (?,?)", OWNER, OTHER);
        jdbcTemplate.update("DELETE FROM agent_turn WHERE user_id IN (?,?)", OWNER, OTHER);
        jdbcTemplate.update("DELETE FROM agent_episode WHERE user_id IN (?,?)", OWNER, OTHER);
        jdbcTemplate.update("DELETE FROM agent_conversation WHERE user_id IN (?,?)", OWNER, OTHER);
        jdbcTemplate.update("DELETE FROM agent_profile WHERE user_id IN (?,?)", OWNER, OTHER);
        jdbcTemplate.update("DELETE FROM agent_run_guard WHERE user_id IN (?,?)", OWNER, OTHER);
        jdbcTemplate.update("DELETE FROM sys_user WHERE id IN (?,?)", OWNER, OTHER);
    }

    @Test
    void authenticatedPostCreatesAnOwnerScopedTurnAndSnapshot() throws Exception {
        HttpHeaders headers = jsonBearer(OWNER);
        ResponseEntity<String> created = restTemplate.postForEntity(url("/api/agent/turns"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000008721",
                         "message":"Explain current community evidence",
                         "temporary":false,
                         "context":{"route":"COMMUNITY_HOME"}}
                        """, headers), String.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = objectMapper.readTree(created.getBody());
        long turnId = Long.parseLong(body.path("turnId").asText());
        assertThat(turnId).as(created.getBody()).isPositive();
        assertThat(body.path("state").asText()).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM agent_turn WHERE id=?",
                Long.class, turnId)).isEqualTo(OWNER);

        ResponseEntity<String> snapshot = restTemplate.exchange(url("/api/agent/turns/" + turnId),
                HttpMethod.GET, new HttpEntity<>(bearer(OWNER)), String.class);
        assertThat(snapshot.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(snapshot.getBody()).contains("\"turnId\":\"" + turnId + "\"",
                "\"state\":\"RUNNING\"", "Explain current community evidence");

        ResponseEntity<String> hidden = restTemplate.exchange(url("/api/agent/turns/" + turnId),
                HttpMethod.GET, new HttpEntity<>(bearer(OTHER)), String.class);
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(hidden.getBody()).contains("RESOURCE_NOT_FOUND");
    }

    @Test
    void eventReplayIsOrderedAndTrimmedPrefixRequiresSnapshotRecovery() {
        var admitted = admissions.admit(new cumt.zongzuo.community.ai.agent.turn.AgentTurnCreateCommand(
                OWNER, UUID.randomUUID(), "question", "{}", "COMMUNITY_QA"));
        String first = events.append(admitted.turnId(), OWNER, admitted.runId(), admitted.runFence(),
                "accepted", java.util.Map.of("state", "RUNNING"));
        String second = events.append(admitted.turnId(), OWNER, admitted.runId(), admitted.runFence(),
                "delta", java.util.Map.of("textAppend", "hello"));

        assertThat(events.replay(admitted.turnId(), OWNER, first, 20))
                .extracting(event -> event.eventId()).containsExactly(second);
        events.trimBefore(admitted.turnId(), second);
        assertThatThrownBy(() -> events.replay(admitted.turnId(), OWNER, first, 20))
                .isInstanceOfSatisfying(AiApiException.class,
                        error -> assertThat(error.code()).isEqualTo("EVENT_STREAM_EXPIRED"));
    }

    @Test
    void cancellationReleasesTheUserGuardAndEmitsATerminalEvent() throws Exception {
        var admitted = admissions.admit(new cumt.zongzuo.community.ai.agent.turn.AgentTurnCreateCommand(
                OWNER, UUID.randomUUID(), "cancel me", "{}", "COMMUNITY_QA"));
        events.append(admitted.turnId(), OWNER, admitted.runId(), admitted.runFence(),
                "accepted", java.util.Map.of("state", "RUNNING"));

        ResponseEntity<String> cancelled = restTemplate.postForEntity(
                url("/api/agent/turns/" + admitted.turnId() + "/cancel"),
                new HttpEntity<>(bearer(OWNER)), String.class);

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody()).contains("\"state\":\"CANCELLED\"");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT active_run_id IS NULL FROM agent_run_guard WHERE user_id=?
                """, Boolean.class, OWNER)).isTrue();
        assertThat(events.replay(admitted.turnId(), OWNER, null, 20))
                .extracting(event -> event.type()).containsExactly("accepted", "cancelled");
    }

    @Test
    void liveEventConnectionWaitsForNewEventsAndClosesOnCancellation() throws Exception {
        var admitted = admissions.admit(new cumt.zongzuo.community.ai.agent.turn.AgentTurnCreateCommand(
                OWNER, UUID.randomUUID(), "stream this", "{}", "COMMUNITY_QA"));
        String accepted = events.append(admitted.turnId(), OWNER, admitted.runId(),
                admitted.runFence(), "accepted", java.util.Map.of("state", "RUNNING"));

        CompletableFuture<ResponseEntity<String>> stream = CompletableFuture.supplyAsync(() ->
                restTemplate.exchange(url("/api/agent/turns/" + admitted.turnId()
                                + "/events?after=" + accepted), HttpMethod.GET,
                        new HttpEntity<>(bearer(OWNER)), String.class));
        Thread.sleep(250);
        String delta = events.append(admitted.turnId(), OWNER, admitted.runId(),
                admitted.runFence(), "delta", java.util.Map.of("textAppend", "live"));
        restTemplate.postForEntity(url("/api/agent/turns/" + admitted.turnId() + "/cancel"),
                new HttpEntity<>(bearer(OWNER)), String.class);

        ResponseEntity<String> response = stream.get(5, TimeUnit.SECONDS);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getBody()).contains("id: " + delta, "event: delta", "event: cancelled");
    }

    @Test
    void saturatedExecutorFailsTheAdmittedTurnAndReleasesTheUserGuard() {
        doThrow(new java.util.concurrent.RejectedExecutionException("full"))
                .when(runner).submit(any(), anyLong(), any());

        ResponseEntity<String> response = restTemplate.postForEntity(url("/api/agent/turns"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000008729",
                         "message":"queue full","temporary":false,"context":{}}
                        """, jsonBearer(OWNER)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("AGENT_RUNTIME_UNAVAILABLE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT active_run_id IS NULL FROM agent_run_guard WHERE user_id=?
                """, Boolean.class, OWNER)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM agent_turn WHERE user_id=? ORDER BY id DESC LIMIT 1
                """, String.class, OWNER)).isEqualTo("FAILED");

        ResponseEntity<String> replay = restTemplate.postForEntity(url("/api/agent/turns"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000008729",
                         "message":"queue full","temporary":false,"context":{}}
                        """, jsonBearer(OWNER)), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).contains("\"state\":\"FAILED\"", "\"created\":false");
    }

    private HttpHeaders jsonBearer(long userId) {
        HttpHeaders headers = bearer(userId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders bearer(long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(userId));
        return headers;
    }
}
