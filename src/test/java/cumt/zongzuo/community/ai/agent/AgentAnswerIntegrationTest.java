package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.ai.provider.AiChatGateway;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.article.chunk.ArticleChunkMaterializationService;
import cumt.zongzuo.community.article.chunk.ArticleChunker;
import cumt.zongzuo.community.article.projection.chunk.ArticleChunkElasticsearchProjectionService;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {
        "metro.ai.enabled=true",
        "metro.ai.agent.enabled=true",
        "metro.ai.memory.enabled=true",
        // 这组用例只验证站内检索、记忆与持久 turn。联网搜索有独立的网关契约测试，
        // 在此显式关闭可避免开发者本地 .env 中的真实密钥影响可重复的集成测试。
        "metro.ai.web-search.enabled=false",
        // 固定严格回答校验的期望模型，不允许开发机 .env 改变测试语义。
        "metro.ai.platform.model=deepseek-v4-flash",
        "metro.ai.embedding.enabled=false",
        "metro.projection.article-chunks.enabled=true",
        "metro.projection.article-chunks.parser-generation=97",
        "metro.projection.article-chunk-elasticsearch.enabled=true",
        "metro.projection.article-chunk-elasticsearch.index-name=article-chunks-agent-test"
})
class AgentAnswerIntegrationTest extends IntegrationTestSupport {

    private static final long USER_ID = 97_001L;
    private static final long ARTICLE_ID = 97_101L;
    private static final long REVISION_ID = 97_201L;
    private static final long PARSER_GENERATION = 97L;

    @Autowired
    private ArticleChunkMaterializationService materialization;

    @Autowired
    private ArticleChunkElasticsearchProjectionService projection;

    @MockitoBean
    private AiChatGateway gateway;

    private long chunkId;
    private Long previousParserGeneration;

    @BeforeEach
    void seedCurrentPublishedKnowledge() {
        cleanupAgentTimeline();
        previousParserGeneration = jdbcTemplate.query("""
                SELECT active_generation FROM article_chunk_parser_checkpoint WHERE checkpoint_id=1
                """, rs -> rs.next() ? rs.getLong(1) : null);
        jdbcTemplate.update("DELETE FROM consumer_inbox WHERE consumer_name='article-chunk-elasticsearch'");
        jdbcTemplate.update("DELETE FROM projection_watermark WHERE consumer_name='article-chunk-elasticsearch' AND aggregate_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_chunk WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_chunk_set WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL WHERE id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article WHERE id=?", ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status)
                VALUES (?,'agent-integration','unused','agent-integration@example.com',0,0)
                ON DUPLICATE KEY UPDATE status=0
                """, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO article(id,title,summary,content,author_id,status,is_deleted,create_time,
                  update_time,visibility_state,review_state,lifecycle_epoch,lock_version)
                VALUES (?,'Transaction locking','Locking summary','compat',?,1,0,NOW(6),NOW(6),
                  'PUBLIC','APPROVED',1,5)
                """, ARTICLE_ID, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO article_revision(id,article_id,revision_no,title,summary,body_markdown,
                  body_plain,cover,tags_json,content_hash,source_draft_version,created_by,created_at)
                VALUES (?,?,1,'Transaction locking','Locking summary',
                  '# Transactions\n\nUse SELECT FOR UPDATE to serialize writers around the current row.',
                  'Transactions Use SELECT FOR UPDATE to serialize writers around the current row.',
                  '', '[]', ?,1,?,?)
                """, REVISION_ID, ARTICLE_ID, "c".repeat(64), USER_ID, LocalDateTime.now());
        jdbcTemplate.update("""
                UPDATE article SET latest_revision_id=?,published_revision_id=? WHERE id=?
                """, REVISION_ID, REVISION_ID, ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO article_chunk_parser_generation
                  (generation,parser_version,token_estimator_version,dependency_fingerprint,
                   required_build_digest,state,operator_identity,created_at,updated_at,lock_version)
                VALUES (?,?,?,?,?,'ACTIVE','agent-test',NOW(6),NOW(6),0)
                ON DUPLICATE KEY UPDATE state='ACTIVE'
                """, PARSER_GENERATION, ArticleChunker.PARSER_VERSION,
                ArticleChunker.TOKEN_ESTIMATOR_VERSION, ArticleChunker.DEPENDENCY_FINGERPRINT,
                "a".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO article_chunk_parser_checkpoint
                  (checkpoint_id,active_generation,lock_version,updated_by,updated_at)
                VALUES (1,?,0,'agent-test',NOW(6))
                ON DUPLICATE KEY UPDATE active_generation=VALUES(active_generation),
                  lock_version=lock_version+1,updated_by='agent-test',updated_at=NOW(6)
                """, PARSER_GENERATION);
        var result = materialization.materialize(ARTICLE_ID, 1L, 5L);
        projection.apply(new DomainEvent(UUID.randomUUID(), "ARTICLE_CHUNK_SET", ARTICLE_ID,
                result.chunkSetVersion(), 1L, DomainEventType.ARTICLE_CHUNK_REINDEX_REQUESTED,
                1, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(), Instant.now()));
        chunkId = jdbcTemplate.queryForObject("""
                SELECT id FROM article_chunk WHERE article_id=? AND is_active=1 ORDER BY chunk_no LIMIT 1
                """, Long.class, ARTICLE_ID);
    }

    @AfterEach
    void removeAgentKnowledgeFixture() {
        cleanupAgentTimeline();
        jdbcTemplate.update("DELETE FROM consumer_inbox WHERE consumer_name='article-chunk-elasticsearch'");
        jdbcTemplate.update("DELETE FROM projection_watermark WHERE consumer_name='article-chunk-elasticsearch' AND aggregate_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_chunk WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_chunk_set WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL WHERE id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article WHERE id=?", ARTICLE_ID);
        if (previousParserGeneration == null) {
            jdbcTemplate.update("DELETE FROM article_chunk_parser_checkpoint WHERE checkpoint_id=1");
        } else {
            jdbcTemplate.update("""
                    UPDATE article_chunk_parser_checkpoint SET active_generation=?,lock_version=lock_version+1,
                      updated_by='agent-test-cleanup',updated_at=NOW(6) WHERE checkpoint_id=1
                    """, previousParserGeneration);
        }
        if (!Long.valueOf(PARSER_GENERATION).equals(previousParserGeneration)) {
            jdbcTemplate.update("DELETE FROM article_chunk_parser_generation WHERE generation=?",
                    PARSER_GENERATION);
        }
    }

    @Test
    void authenticatedRequestRunsRealBm25MysqlRevalidationAndGroundedAnswerValidation() {
        String sourceId = "A" + ARTICLE_ID + ":R" + REVISION_ID + ":C" + chunkId;
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"Use a row lock around the writer transaction.[1]","citations":[
                  {"marker":1,"sourceId":"%s",
                   "quote":"Use SELECT FOR UPDATE to serialize writers"}]}
                """.formatted(sourceId), "stop", 120, 30, "test", "deepseek-v4-flash"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(USER_ID));
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(url("/api/agent/answer"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000000097",
                         "message":"How should writers be serialized with a MySQL row lock?"}
                        """, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Use a row lock", sourceId,
                "Use SELECT FOR UPDATE to serialize writers", "\"url\":\"/article/97101\"");
    }

    @Test
    void staleSearchHitIsDroppedButTheModelMayStillAnswerWithGeneralKnowledge() {
        jdbcTemplate.update("""
                UPDATE article SET is_deleted=1,visibility_state='RECYCLED',status=0,
                  lifecycle_epoch=lifecycle_epoch+1,lock_version=lock_version+1 WHERE id=?
                """, ARTICLE_ID);
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"【模型通用知识】可以使用事务与行锁协调并发写入。","citations":[]}
                """, "stop", 100, 20, "test", "deepseek-v4-flash"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(USER_ID));
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(url("/api/agent/answer"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000000098",
                         "message":"How should writers be serialized with a MySQL row lock?"}
                        """, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("【模型通用知识】", "\"citations\":[]");
        verify(gateway).generate(any());
    }

    @Test
    void persistentTurnRunsInBackgroundAndPublishesOnlyTheCommittedGroundedResult() throws Exception {
        String sourceId = "A" + ARTICLE_ID + ":R" + REVISION_ID + ":C" + chunkId;
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"Use a row lock around the writer transaction.[1]","citations":[
                  {"marker":1,"sourceId":"%s",
                   "quote":"Use SELECT FOR UPDATE to serialize writers"}]}
                """.formatted(sourceId), "stop", 120, 30, "test", "deepseek-v4-flash"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(USER_ID));
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> created = restTemplate.postForEntity(url("/api/agent/turns"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000000099",
                         "message":"How should writers be serialized with a MySQL row lock?",
                         "temporary":false,"context":{"route":"COMMUNITY_HOME"}}
                        """, headers), String.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long turnId = Long.parseLong(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created.getBody()).path("turnId").asText());
        String snapshot = awaitTerminal(turnId, headers);
        assertThat(snapshot).contains("\"state\":\"SUCCEEDED\"", "Use a row lock",
                "\"citationCount\":1");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_message WHERE turn_id=? AND role='ASSISTANT'
                """, Integer.class, turnId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_answer_citation c
                JOIN agent_message m ON m.id=c.assistant_message_id
                WHERE m.turn_id=? AND c.article_id=? AND c.revision_id=? AND c.chunk_id=?
                """, Integer.class, turnId, ARTICLE_ID, REVISION_ID, chunkId)).isOne();
        ResponseEntity<String> stream = restTemplate.exchange(
                url("/api/agent/turns/" + turnId + "/events"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(stream.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stream.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(stream.getBody()).contains("event: accepted", "event: retrieving",
                "event: generating", "event: done");

        // 历史轨道只展示当前用户主对话中已成功持久的问答。
        // 摘要由服务端从权威 message 行生成，前端不需要先下载整段历史。
        ResponseEntity<String> history = restTemplate.exchange(
                url("/api/agent/turns/history?size=10"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody()).contains("\"turnId\":\"" + turnId + "\"",
                "How should writers be serialized", "Use a row lock",
                "\"nextBeforeTurnId\":null");
    }

    @Test
    void persistentTurnsAutomaticallySaveAndRecallLowRiskMemory() throws Exception {
        // 第一轮也会真实走回答生成，因此必须显式给出严格 JSON，
        // 不依赖 Mockito 默认 null 或其它用例留下的 stub。
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"我会记住你偏好简洁的回答。","citations":[]}
                """, "stop", 80, 18, "test", "deepseek-v4-flash"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(USER_ID));
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> first = restTemplate.postForEntity(url("/api/agent/turns"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000000091",
                         "message":"我喜欢简洁的回答风格",
                         "temporary":false,"context":{}}
                        """, headers), String.class);
        long firstTurn = Long.parseLong(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(first.getBody()).path("turnId").asText());
        assertThat(awaitTerminal(firstTurn, headers)).contains("\"state\":\"SUCCEEDED\"");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_memory_item WHERE user_id=? AND state='ACTIVE'
                """, Integer.class, USER_ID)).isOne();
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"你喜欢简洁的回答风格。","citations":[]}
                """, "stop", 100, 20, "test", "deepseek-v4-flash"));

        ResponseEntity<String> recalled = restTemplate.postForEntity(url("/api/agent/answer"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000000092",
                         "message":"你记得我喜欢什么样的回答吗？"}
                        """, headers), String.class);

        assertThat(recalled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recalled.getBody()).contains("你喜欢简洁的回答风格",
                "\"memoryUses\":[", "\"category\":\"PREFERENCE\"");
    }

    private String awaitTerminal(long turnId, HttpHeaders headers) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        String body = null;
        while (System.nanoTime() < deadline) {
            ResponseEntity<String> response = restTemplate.exchange(
                    url("/api/agent/turns/" + turnId), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            body = response.getBody();
            if (body != null && (body.contains("\"state\":\"SUCCEEDED\"")
                    || body.contains("\"state\":\"FAILED\""))) {
                return body;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(
                    java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50));
        }
        throw new AssertionError("Agent turn did not finish: " + body);
    }

    private void cleanupAgentTimeline() {
        jdbcTemplate.update("DELETE FROM agent_memory_projection WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_memory_source WHERE user_id=?", USER_ID);
        jdbcTemplate.update("UPDATE agent_memory_item SET current_version_id=NULL WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_memory_version WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_memory_item WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_memory_setting WHERE user_id=?", USER_ID);
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
    }
}
