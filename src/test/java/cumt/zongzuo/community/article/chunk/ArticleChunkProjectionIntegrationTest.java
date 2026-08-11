package cumt.zongzuo.community.article.chunk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "metro.projection.article-chunks.enabled=true")
class ArticleChunkProjectionIntegrationTest extends IntegrationTestSupport {

    private static final long ARTICLE_ID = 88_201L;
    private static final long REVISION_ID = 88_301L;

    @Autowired
    private RabbitListenerEndpointRegistry listeners;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void stopChunkListener() {
        var listener = listeners.getListenerContainer("articleChunkProjectionConsumer");
        if (listener != null) {
            listener.stop();
        }
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM consumer_inbox WHERE consumer_name='article-chunk-current-pointer'");
        jdbcTemplate.update("DELETE FROM projection_watermark WHERE consumer_name='article-chunk-current-pointer' AND aggregate_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_chunk WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_chunk_set WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL WHERE id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article WHERE id=?", ARTICLE_ID);
    }

    @BeforeEach
    void resetFixture() {
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(RabbitConfig.ARTICLE_CHUNK_FACT_QUEUE);
            channel.queuePurge(RabbitConfig.ARTICLE_CHUNK_FACT_QUEUE + ".dlq");
            return null;
        });
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM consumer_inbox WHERE consumer_name='article-chunk-current-pointer'");
        jdbcTemplate.update("DELETE FROM projection_watermark WHERE consumer_name='article-chunk-current-pointer'");
        jdbcTemplate.update("DELETE FROM article_chunk");
        jdbcTemplate.update("DELETE FROM article_chunk_set");
        jdbcTemplate.update("DELETE FROM article_chunk_parser_checkpoint");
        jdbcTemplate.update("DELETE FROM article_chunk_parser_generation");
        jdbcTemplate.update("""
                UPDATE article
                SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL
                WHERE id=?
                """, ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article WHERE id=?", ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO article(id,title,content,summary,author_id,status,is_deleted,create_time,
                  latest_revision_id,pending_revision_id,published_revision_id,visibility_state,
                  review_state,lifecycle_epoch,lock_version)
                VALUES (?, 'Rabbit标题', 'Rabbit兼容正文', 'Rabbit摘要', 43, 1, 0,
                  CURRENT_TIMESTAMP(6),NULL,NULL,NULL,'PUBLIC','APPROVED',1,7)
                """, ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO article_revision(id,article_id,revision_no,title,summary,body_markdown,
                  body_plain,cover,tags_json,content_hash,source_draft_version,created_by,created_at)
                VALUES (?, ?, 1, 'Rabbit标题', 'Rabbit摘要', '# 标题\n\nRabbit正文',
                  'Rabbit正文', NULL, '[]', ?, 1, 43, CURRENT_TIMESTAMP(6))
                """, REVISION_ID, ARTICLE_ID, "d".repeat(64));
        jdbcTemplate.update("""
                UPDATE article SET latest_revision_id=?,published_revision_id=? WHERE id=?
                """, REVISION_ID, REVISION_ID, ARTICLE_ID);
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
        var listener = listeners.getListenerContainer("articleChunkProjectionConsumer");
        assertThat(listener).isNotNull();
        listener.start();
    }

    @Test
    void rabbitDeliveryCreatesExactlyOneFactSetAndSanitizedDerivedEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        DomainEvent event = new DomainEvent(eventId, "ARTICLE", ARTICLE_ID, 7L, 1L,
                DomainEventType.ARTICLE_REVISION_PUBLISHED, 1,
                JsonNodeFactory.instance.objectNode(), Instant.now());

        rabbitTemplate.convertAndSend(RabbitConfig.ARTICLE_CHUNK_FACT_QUEUE, event);
        rabbitTemplate.convertAndSend(RabbitConfig.ARTICLE_CHUNK_FACT_QUEUE, event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM article_chunk_set WHERE article_id=?",
                    Integer.class, ARTICLE_ID)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM consumer_inbox
                    WHERE consumer_name='article-chunk-current-pointer' AND event_id=UUID_TO_BIN(?)
                    """, Integer.class, eventId.toString())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM domain_event_outbox
                    WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?
                    """, Integer.class, ARTICLE_ID)).isEqualTo(1);
        });
        String payload = jdbcTemplate.queryForObject("""
                SELECT payload_json FROM domain_event_outbox
                WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?
                """, String.class, ARTICLE_ID);
        assertThat(objectMapper.readTree(payload).path("parserGeneration").asLong()).isEqualTo(1L);
        assertThat(objectMapper.readTree(payload).path("activeChunkCount").asInt()).isPositive();
        assertThat(payload)
                .doesNotContain("Rabbit正文", "bodyText", "bodyMarkdown");
        assertThat(rabbitTemplate.receive(RabbitConfig.ARTICLE_CHUNK_FACT_QUEUE + ".dlq", 200))
                .isNull();
    }
}
