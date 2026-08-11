package cumt.zongzuo.community.article.projection.chunk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.article.chunk.ArticleChunkMaterializationService;
import cumt.zongzuo.community.article.chunk.ArticleChunker;
import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@TestPropertySource(properties = {
        "metro.projection.article-chunks.enabled=true",
        "metro.projection.article-chunk-elasticsearch.enabled=true"
})
class ElasticsearchArticleChunkProjectionIntegrationTest extends IntegrationTestSupport {

    private static final long ARTICLE_ID = 89_001L;
    private static final long REVISION_ONE = 89_101L;
    private static final long REVISION_TWO = 89_102L;
    private static final String INDEX = "article-chunks-v1";

    @Autowired
    private ArticleChunkMaterializationService materializationService;

    @Autowired
    private ArticleChunkElasticsearchProjectionService projectionService;

    @MockitoSpyBean
    private ElasticsearchArticleChunkRepository repository;

    @Autowired
    private RestClient restClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry listeners;

    @BeforeEach
    void seedPublishedArticleAndActiveParser() throws Exception {
        reset(repository);
        repository.ensureCompatible();
        deleteArticleDocuments();
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(RabbitConfig.ARTICLE_CHUNK_ELASTICSEARCH_QUEUE);
            channel.queuePurge(RabbitConfig.ARTICLE_CHUNK_ELASTICSEARCH_QUEUE + ".dlq");
            return null;
        });
        jdbcTemplate.update("DELETE FROM consumer_inbox WHERE consumer_name=?",
                ArticleChunkElasticsearchProjectionService.CONSUMER);
        jdbcTemplate.update("DELETE FROM projection_watermark WHERE consumer_name=?",
                ArticleChunkElasticsearchProjectionService.CONSUMER);
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?",
                ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_chunk WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_chunk_set WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL WHERE id=?",
                ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article WHERE id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_chunk_parser_checkpoint");
        jdbcTemplate.update("DELETE FROM article_chunk_parser_generation");

        jdbcTemplate.update("""
                INSERT INTO article(id,title,content,summary,author_id,status,is_deleted,create_time,
                  latest_revision_id,pending_revision_id,published_revision_id,visibility_state,
                  review_state,lifecycle_epoch,lock_version)
                VALUES (?, '第一版标题', '兼容正文', '摘要', 42, 1, 0, CURRENT_TIMESTAMP(6),
                  NULL,NULL,NULL,'PUBLIC','APPROVED',1,5)
                """, ARTICLE_ID);
        insertRevision(REVISION_ONE, 1, "第一版标题", "# 总览\n\n第一版公开正文\n\n## 细节\n\n第一版尾部", "b".repeat(64));
        jdbcTemplate.update("UPDATE article SET latest_revision_id=?,published_revision_id=? WHERE id=?",
                REVISION_ONE, REVISION_ONE, ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO article_chunk_parser_generation
                  (generation,parser_version,token_estimator_version,dependency_fingerprint,
                   required_build_digest,state,operator_identity,created_at,updated_at,lock_version)
                VALUES (1,?,?,?,?, 'ACTIVE','test',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0)
                """, ArticleChunker.PARSER_VERSION, ArticleChunker.TOKEN_ESTIMATOR_VERSION,
                ArticleChunker.DEPENDENCY_FINGERPRINT, "a".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO article_chunk_parser_checkpoint
                  (checkpoint_id,active_generation,lock_version,updated_by,updated_at)
                VALUES (1,1,0,'test',CURRENT_TIMESTAMP(6))
                """);
    }

    @AfterEach
    void stopProjectionListener() {
        var listener = listeners.getListenerContainer("articleChunkElasticsearchProjectionConsumer");
        if (listener != null) {
            listener.stop();
        }
    }

    @Test
    void projectsReplacementAndTombstoneIntoAnIndependentMonotonicIndex() throws Exception {
        var first = materializationService.materialize(ARTICLE_ID, 1L, 5L);
        DomainEvent firstEvent = event(first.chunkSetVersion(), 1L);

        assertThat(projectionService.apply(firstEvent)).isEqualTo(ProjectionLease.Decision.ACQUIRED);
        JsonNode firstSearch = searchActive();
        assertThat(firstSearch.path("hits").path("total").path("value").asInt())
                .isEqualTo(first.activeChunkCount());
        assertThat(firstSearch.path("hits").path("hits").toString())
                .contains("第一版公开正文");

        insertRevision(REVISION_TWO, 2, "第二版标题", "# 新版\n\n第二版公开正文，只允许这一版保持 active", "c".repeat(64));
        jdbcTemplate.update("""
                UPDATE article SET title='第二版标题',content='第二版公开正文',
                  latest_revision_id=?,published_revision_id=?,lock_version=6
                WHERE id=?
                """, REVISION_TWO, REVISION_TWO, ARTICLE_ID);
        var second = materializationService.materialize(ARTICLE_ID, 1L, 6L);
        DomainEvent secondEvent = event(second.chunkSetVersion(), 1L);

        assertThat(projectionService.apply(secondEvent)).isEqualTo(ProjectionLease.Decision.ACQUIRED);
        JsonNode replacement = searchAll();
        assertThat(replacement.path("hits").path("hits").toString())
                .contains("第二版公开正文")
                .contains("\"active\":false");
        assertThat(searchActive().path("hits").path("total").path("value").asInt())
                .isEqualTo(second.activeChunkCount());

        jdbcTemplate.update("""
                UPDATE article SET published_revision_id=NULL,status=0,visibility_state='PRIVATE',
                  review_state='NOT_SUBMITTED',lifecycle_epoch=2,lock_version=7
                WHERE id=?
                """, ARTICLE_ID);
        var tombstone = materializationService.materialize(ARTICLE_ID, 2L, 7L);
        DomainEvent tombstoneEvent = event(tombstone.chunkSetVersion(), 2L);

        assertThat(projectionService.apply(tombstoneEvent)).isEqualTo(ProjectionLease.Decision.ACQUIRED);
        assertThat(searchActive().path("hits").path("total").path("value").asInt()).isZero();
        assertThat(projectionService.apply(tombstoneEvent)).isEqualTo(ProjectionLease.Decision.DUPLICATE);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM consumer_inbox
                WHERE consumer_name=? AND event_id=UUID_TO_BIN(?)
                """, Integer.class, ArticleChunkElasticsearchProjectionService.CONSUMER,
                tombstoneEvent.eventId().toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT CONCAT(lifecycle_epoch,':',last_applied_version,':',tombstone)
                FROM projection_watermark
                WHERE consumer_name=? AND aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?
                """, String.class, ArticleChunkElasticsearchProjectionService.CONSUMER, ARTICLE_ID))
                .isEqualTo("2:" + tombstone.chunkSetVersion() + ":1");
    }

    @Test
    void expiredOldWorkerCannotReviveChunksAfterANewerTombstone() throws Exception {
        var first = materializationService.materialize(ARTICLE_ID, 1L, 5L);
        DomainEvent staleEvent = event(first.chunkSetVersion(), 1L);
        CountDownLatch staleEffectReached = new CountDownLatch(1);
        CountDownLatch releaseStaleEffect = new CountDownLatch(1);
        doAnswer(invocation -> {
            ArticleChunkSearchSource.Snapshot snapshot = invocation.getArgument(0);
            if (snapshot.chunkSetVersion() == first.chunkSetVersion()) {
                staleEffectReached.countDown();
                assertThat(releaseStaleEffect.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(repository).replace(any());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stale = executor.submit(() -> projectionService.apply(staleEvent));
            assertThat(staleEffectReached.await(10, TimeUnit.SECONDS)).isTrue();
            jdbcTemplate.update("""
                    UPDATE projection_watermark
                    SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)
                    WHERE consumer_name=? AND aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?
                    """, ArticleChunkElasticsearchProjectionService.CONSUMER, ARTICLE_ID);
            jdbcTemplate.update("""
                    UPDATE article SET published_revision_id=NULL,status=0,visibility_state='PRIVATE',
                      review_state='NOT_SUBMITTED',lifecycle_epoch=2,lock_version=7
                    WHERE id=?
                    """, ARTICLE_ID);
            var tombstone = materializationService.materialize(ARTICLE_ID, 2L, 7L);

            assertThat(projectionService.apply(event(tombstone.chunkSetVersion(), 2L)))
                    .isEqualTo(ProjectionLease.Decision.ACQUIRED);
            releaseStaleEffect.countDown();
            assertThatThrownBy(() -> stale.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(RuntimeException.class);
        } finally {
            releaseStaleEffect.countDown();
            reset(repository);
        }
        assertThat(searchActive().path("hits").path("total").path("value").asInt()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT CONCAT(lifecycle_epoch,':',last_applied_version,':',tombstone)
                FROM projection_watermark
                WHERE consumer_name=? AND aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?
                """, String.class, ArticleChunkElasticsearchProjectionService.CONSUMER, ARTICLE_ID))
                .startsWith("2:").endsWith(":1");
    }

    @Test
    void dedicatedRabbitDeliveryProjectsTheChunkFactExactlyOnce() {
        var materialized = materializationService.materialize(ARTICLE_ID, 1L, 5L);
        DomainEvent event = event(materialized.chunkSetVersion(), 1L);
        var listener = listeners.getListenerContainer("articleChunkElasticsearchProjectionConsumer");
        assertThat(listener).isNotNull();
        listener.start();

        rabbitTemplate.convertAndSend(RabbitConfig.ARTICLE_CHUNK_ELASTICSEARCH_QUEUE, event);
        rabbitTemplate.convertAndSend(RabbitConfig.ARTICLE_CHUNK_ELASTICSEARCH_QUEUE, event);

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(searchActive().path("hits").path("total").path("value").asInt())
                    .isEqualTo(materialized.activeChunkCount());
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM consumer_inbox
                    WHERE consumer_name=? AND event_id=UUID_TO_BIN(?)
                    """, Integer.class, ArticleChunkElasticsearchProjectionService.CONSUMER,
                    event.eventId().toString())).isEqualTo(1);
        });
        assertThat(rabbitTemplate.receive(RabbitConfig.ARTICLE_CHUNK_ELASTICSEARCH_QUEUE + ".dlq", 200))
                .isNull();
    }

    private void insertRevision(long revisionId, int revisionNo, String title, String body, String hash) {
        jdbcTemplate.update("""
                INSERT INTO article_revision(id,article_id,revision_no,title,summary,body_markdown,
                  body_plain,cover,tags_json,content_hash,source_draft_version,created_by,created_at)
                VALUES (?,?,?,?,'摘要',?,?,NULL,'[]',?,?,42,CURRENT_TIMESTAMP(6))
                """, revisionId, ARTICLE_ID, revisionNo, title, body, body.replace("#", ""), hash,
                revisionNo);
    }

    private DomainEvent event(long version, long epoch) {
        return new DomainEvent(UUID.randomUUID(), "ARTICLE_CHUNK_SET", ARTICLE_ID, version, epoch,
                DomainEventType.ARTICLE_CHUNK_REINDEX_REQUESTED, 1,
                JsonNodeFactory.instance.objectNode(), Instant.now());
    }

    private JsonNode searchActive() throws Exception {
        Request request = new Request("GET", "/" + INDEX + "/_search");
        request.setJsonEntity("{\"size\":100,\"query\":{\"term\":{\"active\":true}}}");
        return response(request);
    }

    private JsonNode searchAll() throws Exception {
        Request request = new Request("GET", "/" + INDEX + "/_search");
        request.setJsonEntity("{\"size\":100,\"query\":{\"match_all\":{}}}");
        return response(request);
    }

    private JsonNode response(Request request) throws Exception {
        return objectMapper.readTree(EntityUtils.toString(restClient.performRequest(request).getEntity()));
    }

    private void deleteArticleDocuments() throws Exception {
        Request request = new Request("POST", "/" + INDEX + "/_delete_by_query");
        request.addParameter("refresh", "true");
        request.setJsonEntity("{\"query\":{\"term\":{\"articleId\":" + ARTICLE_ID + "}}}");
        try {
            restClient.performRequest(request);
        } catch (ResponseException exception) {
            if (exception.getResponse().getStatusLine().getStatusCode() != 404) {
                throw exception;
            }
        }
    }
}
