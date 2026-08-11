package cumt.zongzuo.community.article.chunk;

import cumt.zongzuo.community.IntegrationTestSupport;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import cumt.zongzuo.community.event.projection.ProjectionLeaseService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@TestPropertySource(properties = "metro.projection.article-chunks.enabled=true")
class ArticleChunkMaterializationIntegrationTest extends IntegrationTestSupport {

    private static final long ARTICLE_ID = 88_001L;
    private static final long REVISION_ID = 88_101L;

    @MockitoSpyBean
    private ArticleChunkMaterializationService materializationService;

    @Autowired
    private ArticleChunkProjectionService projectionService;

    @Autowired
    private ProjectionLeaseService leaseService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void seedPublishedRevisionAndParser() {
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM consumer_inbox WHERE consumer_name='article-chunk-current-pointer'");
        jdbcTemplate.update("DELETE FROM projection_watermark WHERE consumer_name='article-chunk-current-pointer'");
        jdbcTemplate.update("DELETE FROM article_chunk");
        jdbcTemplate.update("DELETE FROM article_chunk_set");
        jdbcTemplate.update("""
                DELETE a FROM article_moderation_attempt a
                JOIN article_moderation_job j ON j.id=a.job_id
                WHERE j.article_id=?
                """, ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_moderation_job WHERE article_id=?", ARTICLE_ID);
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
                VALUES (?, '标题', '兼容正文', '摘要', 42, 1, 0, CURRENT_TIMESTAMP(6),
                  NULL,NULL,NULL,'PUBLIC','APPROVED',1,5)
                """, ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO article_revision(id,article_id,revision_no,title,summary,body_markdown,
                  body_plain,cover,tags_json,content_hash,source_draft_version,created_by,created_at)
                VALUES (?, ?, 1, '标题', '摘要', '# 总览\n\n公开正文 😀\n\n## 细节\n\n更多内容',
                  '公开正文 😀 更多内容', NULL, '[]', ?, 1, 42, CURRENT_TIMESTAMP(6))
                """, REVISION_ID, ARTICLE_ID, "b".repeat(64));
        jdbcTemplate.update("""
                UPDATE article SET latest_revision_id=?,published_revision_id=? WHERE id=?
                """, REVISION_ID, REVISION_ID, ARTICLE_ID);
        insertGeneration(1L, "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO article_chunk_parser_checkpoint
                  (checkpoint_id,active_generation,lock_version,updated_by,updated_at)
                VALUES (1,1,0,'test',CURRENT_TIMESTAMP(6))
                """);
    }

    @AfterAll
    void removePublishedRevisionFixture() {
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM consumer_inbox WHERE consumer_name='article-chunk-current-pointer'");
        jdbcTemplate.update("DELETE FROM projection_watermark WHERE consumer_name='article-chunk-current-pointer' AND aggregate_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_chunk WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_chunk_set WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("""
                DELETE a FROM article_moderation_attempt a
                JOIN article_moderation_job j ON j.id=a.job_id
                WHERE j.article_id=?
                """, ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_moderation_job WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL WHERE id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article WHERE id=?", ARTICLE_ID);
    }

    @Test
    void articleEventAtomicallyMaterializesDerivedFactsInboxAndWatermark() {
        UUID eventId = UUID.randomUUID();
        DomainEvent event = new DomainEvent(eventId, "ARTICLE", ARTICLE_ID, 5L, 1L,
                DomainEventType.ARTICLE_REVISION_PUBLISHED, 1,
                JsonNodeFactory.instance.objectNode(), Instant.now());

        assertThat(projectionService.apply(event).materialized()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM consumer_inbox
                WHERE consumer_name='article-chunk-current-pointer' AND event_id=UUID_TO_BIN(?)
                """, Integer.class, eventId.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT last_applied_version FROM projection_watermark
                WHERE consumer_name='article-chunk-current-pointer'
                  AND aggregate_type='ARTICLE' AND aggregate_id=?
                """, Long.class, ARTICLE_ID)).isEqualTo(5L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?
                  AND event_type='ARTICLE_CHUNK_REINDEX_REQUESTED'
                """, Integer.class, ARTICLE_ID)).isEqualTo(1);
    }

    @Test
    void materializesIdempotentlyAndOldWorkerCannotWriteAfterGenerationAdvance() {
        assertThat(materializationService.materialize(ARTICLE_ID, 1L, 5L).applied()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_chunk WHERE article_id=? AND is_active=1",
                Integer.class, ARTICLE_ID)).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT chunk_set_version FROM article_chunk_set WHERE article_id=?",
                Long.class, ARTICLE_ID)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?
                """, Integer.class, ARTICLE_ID)).isEqualTo(1);

        assertThat(materializationService.materialize(ARTICLE_ID, 1L, 5L).applied()).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?
                """, Integer.class, ARTICLE_ID)).isEqualTo(1);

        insertGeneration(2L, "ACTIVE");
        jdbcTemplate.update("""
                UPDATE article_chunk_parser_generation SET state='DRAINING' WHERE generation=1
                """);
        jdbcTemplate.update("""
                UPDATE article_chunk_parser_checkpoint
                SET active_generation=2,lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP(6)
                WHERE checkpoint_id=1
                """);

        assertThatThrownBy(() -> materializationService.materialize(ARTICLE_ID, 1L, 5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local article chunk parser");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT chunk_set_version FROM article_chunk_set WHERE article_id=?",
                Long.class, ARTICLE_ID)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_chunk WHERE article_id=? AND is_active=1 AND parser_generation=1",
                Integer.class, ARTICLE_ID)).isPositive();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?
                """, Integer.class, ARTICLE_ID)).isEqualTo(1);
    }

    @Test
    void freezesTheHumanApprovalTimeAsPublishedAt() {
        LocalDateTime reviewedAt = LocalDateTime.of(2026, 8, 1, 12, 34, 56, 123_456_000);
        jdbcTemplate.update("""
                INSERT INTO article_moderation_job
                  (article_id,revision_id,content_hash,state,attempt_count,reviewer_id,
                   reviewed_at,created_at,updated_at,lock_version)
                VALUES (?,?,?,'HUMAN_APPROVED',0,7,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),1)
                """, ARTICLE_ID, REVISION_ID, "b".repeat(64), reviewedAt);

        materializationService.materialize(ARTICLE_ID, 1L, 5L);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at FROM article_chunk_set WHERE article_id=?",
                LocalDateTime.class, ARTICLE_ID)).isEqualTo(reviewedAt);
        assertThat(jdbcTemplate.queryForList(
                "SELECT DISTINCT published_at FROM article_chunk WHERE article_id=? AND is_active=1",
                LocalDateTime.class, ARTICLE_ID)).containsExactly(reviewedAt);
    }

    @Test
    void factProjectionUsesArticleBeforeWatermarkLockOrder() throws Exception {
        CountDownLatch articleLocked = new CountDownLatch(1);
        CountDownLatch projectionReachedArticle = new CountDownLatch(1);
        doAnswer(invocation -> {
            projectionReachedArticle.countDown();
            return invocation.callRealMethod();
        }).when(materializationService).materialize(anyLong(), anyLong(), anyLong());
        DomainEvent event = new DomainEvent(UUID.randomUUID(), "ARTICLE", ARTICLE_ID, 5L, 1L,
                DomainEventType.ARTICLE_REVISION_PUBLISHED, 1,
                JsonNodeFactory.instance.objectNode(), Instant.now());
        DomainEvent competing = new DomainEvent(UUID.randomUUID(), "ARTICLE", ARTICLE_ID, 4L, 1L,
                DomainEventType.ARTICLE_REVISION_PUBLISHED, 1,
                JsonNodeFactory.instance.objectNode(), Instant.now());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM article WHERE id=? FOR UPDATE",
                        Long.class, ARTICLE_ID);
                articleLocked.countDown();
                try {
                    assertThat(projectionReachedArticle.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return leaseService.acquire("article-chunk-current-pointer", competing,
                        Duration.ofSeconds(2));
            }));
            assertThat(articleLocked.await(5, TimeUnit.SECONDS)).isTrue();
            var projection = executor.submit(() -> projectionService.apply(event));

            assertThat(holder.get(10, TimeUnit.SECONDS).decision())
                    .isEqualTo(ProjectionLease.Decision.ACQUIRED);
            assertThat(projection.get(10, TimeUnit.SECONDS).decision())
                    .isEqualTo(ProjectionLease.Decision.BUSY);
        } finally {
            reset(materializationService);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_chunk_set WHERE article_id=?",
                Integer.class, ARTICLE_ID)).isZero();
    }

    @Test
    void localParserIdentityMustExactlyMatchTheActiveGeneration() {
        jdbcTemplate.update("""
                UPDATE article_chunk_parser_generation
                SET dependency_fingerprint=? WHERE generation=1
                """, "e".repeat(64));

        assertThatThrownBy(() -> materializationService.materialize(ARTICLE_ID, 1L, 5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local article chunk parser");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_chunk_set WHERE article_id=?",
                Integer.class, ARTICLE_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?
                """, Integer.class, ARTICLE_ID)).isZero();
    }

    private void insertGeneration(long generation, String state) {
        jdbcTemplate.update("""
                INSERT INTO article_chunk_parser_generation
                  (generation,parser_version,token_estimator_version,dependency_fingerprint,
                   required_build_digest,state,operator_identity,created_at,updated_at,lock_version)
                VALUES (?, ?, ?, ?, ?, ?, 'test', CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0)
                """, generation, ArticleChunker.PARSER_VERSION,
                ArticleChunker.TOKEN_ESTIMATOR_VERSION,
                ArticleChunker.DEPENDENCY_FINGERPRINT, "a".repeat(64), state);
    }
}
