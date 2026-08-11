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
    void staleSearchHitIsDroppedAfterTheArticleStopsBeingPublic() {
        jdbcTemplate.update("""
                UPDATE article SET is_deleted=1,visibility_state='RECYCLED',status=0,
                  lifecycle_epoch=lifecycle_epoch+1,lock_version=lock_version+1 WHERE id=?
                """, ARTICLE_ID);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(USER_ID));
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(url("/api/agent/answer"),
                new HttpEntity<>("""
                        {"clientRequestId":"00000000-0000-0000-0000-000000000098",
                         "message":"How should writers be serialized with a MySQL row lock?"}
                        """, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("现有社区资料不足", "\"citations\":[]");
        verify(gateway, never()).generate(any());
    }
}
