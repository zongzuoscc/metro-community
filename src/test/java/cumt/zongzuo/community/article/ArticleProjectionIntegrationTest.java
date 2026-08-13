package cumt.zongzuo.community.article;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.article.projection.ArticleIndexMappingGuard;
import cumt.zongzuo.community.article.projection.ArticleProjectionProperties;
import cumt.zongzuo.community.article.projection.ArticleSearchProjectionReconciler;
import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.document.ArticleDoc;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import cumt.zongzuo.community.event.projection.ProjectionLeaseService;
import cumt.zongzuo.community.mq.EsSyncConsumer;
import cumt.zongzuo.community.repository.ArticleRepository;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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

@TestPropertySource(properties = {
        "metro.article.revision-mode=CUTOVER",
        "metro.article.projection.lease-duration=PT1S",
        "metro.article.projection.retry.initial-interval=PT0.25S",
        "metro.article.projection.retry.max-interval=PT0.25S",
        "metro.article.projection.retry.max-attempts=8"
})
class ArticleProjectionIntegrationTest extends IntegrationTestSupport {

    private static final long AUTHOR_ID = 96_000L;
    private static final long REVIEWER_ID = 96_001L;
    private static final long ARTICLE_ID = 96_100L;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ArticleIndexMappingGuard indexMappingGuard;
    @Autowired
    private RestClient elasticsearchRestClient;
    @Autowired
    private ArticleSearchProjectionReconciler projectionReconciler;
    @Autowired
    private ProjectionLeaseService projectionLeaseService;
    @Autowired
    private EsSyncConsumer legacyEsSyncConsumer;

    @BeforeAll
    void startProjectionListener() {
        assertThat(listenerRegistry.getListenerContainer("articleSearchProjectionConsumer"))
                .as("the current-pointer projection must own an isolated listener")
                .isNotNull();
        assertThat(listenerRegistry.getListenerContainer("articleModerationNotificationConsumer"))
                .as("moderation event facts need an isolated exactly-once listener")
                .isNotNull();
        listenerRegistry.getListenerContainer("articleSearchProjectionConsumer").start();
        listenerRegistry.getListenerContainer("articleModerationNotificationConsumer").start();
    }

    @AfterAll
    void stopProjectionListener() {
        var listener = listenerRegistry.getListenerContainer("articleSearchProjectionConsumer");
        if (listener != null) {
            listener.stop();
        }
        var notification = listenerRegistry.getListenerContainer("articleModerationNotificationConsumer");
        if (notification != null) {
            notification.stop();
        }
    }

    @BeforeEach
    void resetFixture() {
        cleanupFixture();
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status,deleted)
                VALUES(?, 'projection-author', 'unused', 'projection-author@example.com', 0, 0, 0)
                ON DUPLICATE KEY UPDATE status=0,deleted=0
                """, AUTHOR_ID);
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status,deleted)
                VALUES(?, 'projection-reviewer', 'unused', 'projection-reviewer@example.com', 1, 0, 0)
                ON DUPLICATE KEY UPDATE status=0,deleted=0
                """, REVIEWER_ID);
    }

    @AfterEach
    void cleanupFixture() {
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(RabbitConfig.ARTICLE_SEARCH_PROJECTION_QUEUE);
            channel.queuePurge(RabbitConfig.ARTICLE_SEARCH_PROJECTION_QUEUE + ".dlq");
            channel.queuePurge(RabbitConfig.ARTICLE_MODERATION_NOTIFICATION_QUEUE);
            channel.queuePurge(RabbitConfig.ARTICLE_MODERATION_NOTIFICATION_QUEUE + ".dlq");
            return null;
        });
        articleRepository.deleteById(ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM consumer_inbox WHERE consumer_name='article-search-current-pointer'");
        jdbcTemplate.update("DELETE FROM consumer_inbox WHERE consumer_name='article-moderation-notification'");
        jdbcTemplate.update("DELETE FROM projection_watermark WHERE consumer_name='article-search-current-pointer'");
        jdbcTemplate.update("DELETE FROM message WHERE target_id=? AND type=4", ARTICLE_ID);
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL WHERE id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_moderation_job WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_draft WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article WHERE id=?", ARTICLE_ID);
    }

    @Test
    void publishedEventProjectsTheCurrentMysqlPointerWithRevisionIdentity() {
        long revisionId = seedPublishedArticle("published-v1", "a".repeat(64));
        DomainEvent event = event(1L, DomainEventType.ARTICLE_REVISION_PUBLISHED,
                objectMapper.createObjectNode()
                        .put("articleId", ARTICLE_ID)
                        .put("revisionId", revisionId)
                        .put("contentHash", "a".repeat(64)));

        sendSearch(event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var storedResult = articleRepository.findById(ARTICLE_ID);
            assertThat(storedResult).isPresent();
            ArticleDoc stored = storedResult.orElseThrow();
            assertThat(stored.getTitle()).isEqualTo("published-v1");
            assertThat(stored.getRevisionId()).isEqualTo(revisionId);
            assertThat(stored.getContentHash()).isEqualTo("a".repeat(64));
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM consumer_inbox
                    WHERE consumer_name='article-search-current-pointer'
                    """, Long.class)).isEqualTo(1L);
        });
    }

    @Test
    void duplicateHumanApprovalCreatesOneMessageBoundToTheOriginalEventUuid() {
        String hash = "b".repeat(64);
        long revisionId = seedPublishedArticle("approved-title", hash);
        jdbcTemplate.update("""
                INSERT INTO article_moderation_job(article_id,revision_id,content_hash,state,
                    attempt_count,reviewer_id,review_reason,reviewed_at,created_at,updated_at,lock_version)
                VALUES(?,?,?,'HUMAN_APPROVED',0,?,'approved',NOW(6),NOW(6),NOW(6),1)
                """, ARTICLE_ID, revisionId, hash, REVIEWER_ID);
        long jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_moderation_job WHERE article_id=? AND revision_id=?",
                Long.class, ARTICLE_ID, revisionId);
        DomainEvent event = event(2L, DomainEventType.ARTICLE_REVISION_PUBLISHED,
                objectMapper.createObjectNode()
                        .put("articleId", ARTICLE_ID)
                        .put("revisionId", revisionId)
                        .put("moderationJobId", jobId)
                        .put("contentHash", hash));

        rabbitTemplate.convertAndSend(RabbitConfig.DOMAIN_EVENT_EXCHANGE,
                event.eventType().routingKey(), event);
        rabbitTemplate.convertAndSend(RabbitConfig.DOMAIN_EVENT_EXCHANGE,
                event.eventType().routingKey(), event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM message WHERE source_event_id=UNHEX(REPLACE(?, '-', ''))
                    """, Long.class, event.eventId().toString())).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM consumer_inbox
                    WHERE consumer_name='article-moderation-notification'
                      AND event_id=UNHEX(REPLACE(?, '-', ''))
                    """, Long.class, event.eventId().toString())).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM consumer_inbox
                    WHERE consumer_name='article-search-current-pointer'
                      AND event_id=UNHEX(REPLACE(?, '-', ''))
                    """, Long.class, event.eventId().toString())).isEqualTo(1L);
        });
    }

    @Test
    void duplicateHumanRejectionNotifiesOnceAndKeepsTheOldPublishedPointer() {
        String publicHash = "9".repeat(64);
        long publicRevision = seedPublishedArticle("still-public", publicHash);
        String rejectedHash = "0".repeat(64);
        long rejectedRevision = insertRevision(2L, "rejected-replacement", rejectedHash);
        jdbcTemplate.update("""
                UPDATE article SET latest_revision_id=?,pending_revision_id=NULL,
                    published_revision_id=?,lock_version=2 WHERE id=?
                """, rejectedRevision, publicRevision, ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO article_moderation_job(article_id,revision_id,content_hash,state,
                    attempt_count,reviewer_id,review_reason,reviewed_at,created_at,updated_at,lock_version)
                VALUES(?,?,?,'HUMAN_REJECTED',0,?,'policy',NOW(6),NOW(6),NOW(6),1)
                """, ARTICLE_ID, rejectedRevision, rejectedHash, REVIEWER_ID);
        long jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_moderation_job WHERE article_id=? AND revision_id=?",
                Long.class, ARTICLE_ID, rejectedRevision);
        DomainEvent event = event(2L, DomainEventType.ARTICLE_REVISION_REJECTED,
                objectMapper.createObjectNode()
                        .put("articleId", ARTICLE_ID)
                        .put("revisionId", rejectedRevision)
                        .put("moderationJobId", jobId)
                        .put("contentHash", rejectedHash));

        sendRouted(event);
        sendRouted(event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            // 两次相同事件会被两个异步消费者并发处理；投影短暂不可见时应继续等待收敛，
            // 而不是让 Optional.orElseThrow() 提前终止 Awaitility 的重试。
            var result = articleRepository.findById(ARTICLE_ID);
            assertThat(result).isPresent();
            ArticleDoc stored = result.orElseThrow();
            assertThat(stored.getRevisionId()).isEqualTo(publicRevision);
            assertThat(stored.getContentHash()).isEqualTo(publicHash);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM message
                    WHERE source_event_id=UNHEX(REPLACE(?, '-', ''))
                    """, Long.class, event.eventId().toString())).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM consumer_inbox
                    WHERE consumer_name IN ('article-search-current-pointer',
                                             'article-moderation-notification')
                      AND event_id=UNHEX(REPLACE(?, '-', ''))
                    """, Long.class, event.eventId().toString())).isEqualTo(2L);
        });
    }

    @Test
    void identityMappingIsIdempotentAndConflictingStoredTypesFailClosed() throws Exception {
        String compatibleIndex = "article-projection-compatible";
        deleteIndexIfPresent(compatibleIndex);
        elasticsearchRestClient.performRequest(new Request("PUT", "/" + compatibleIndex));

        indexMappingGuard.ensureCompatible(compatibleIndex);
        indexMappingGuard.ensureCompatible(compatibleIndex);

        Request readMapping = new Request("GET", "/" + compatibleIndex + "/_mapping");
        var mapping = objectMapper.readTree(EntityUtils.toString(
                elasticsearchRestClient.performRequest(readMapping).getEntity()));
        assertThat(mapping.path(compatibleIndex).path("mappings").path("properties")
                .path("revisionId").path("type").asText()).isEqualTo("long");
        assertThat(mapping.path(compatibleIndex).path("mappings").path("properties")
                .path("contentHash").path("type").asText()).isEqualTo("keyword");
        assertThat(mapping.path(compatibleIndex).path("mappings").path("properties")
                .path("projectionLifecycleEpoch").path("type").asText()).isEqualTo("long");
        assertThat(mapping.path(compatibleIndex).path("mappings").path("properties")
                .path("projectionVersion").path("type").asText()).isEqualTo("long");
        assertThat(mapping.path(compatibleIndex).path("mappings").path("properties")
                .path("projectionTombstone").path("type").asText()).isEqualTo("boolean");

        String conflictingIndex = "article-projection-conflict";
        deleteIndexIfPresent(conflictingIndex);
        Request createConflict = new Request("PUT", "/" + conflictingIndex);
        createConflict.setJsonEntity("""
                {"mappings":{"properties":{"revisionId":{"type":"text"},
                "contentHash":{"type":"keyword"}}}}
                """);
        elasticsearchRestClient.performRequest(createConflict);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> indexMappingGuard.ensureCompatible(conflictingIndex))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mapping");

        ArticleProjectionProperties divergentIndex = new ArticleProjectionProperties();
        divergentIndex.setIndexName("article-projection-shadow");
        org.assertj.core.api.Assertions.assertThatThrownBy(divergentIndex::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be article");
    }

    @Test
    void boundedReconcileProjectsBackfilledPointersThatHaveNoDomainEvent() {
        String hash = "c".repeat(64);
        long revisionId = seedPublishedArticle("backfilled-without-event", hash);
        ArticleDoc legacy = new ArticleDoc();
        legacy.setId(ARTICLE_ID);
        legacy.setTitle("stale-legacy-document");
        legacy.setContent("stale");
        articleRepository.save(legacy);

        ArticleSearchProjectionReconciler.BatchResult result =
                projectionReconciler.reconcileAfter(ARTICLE_ID - 1, 1);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.maximumBatchSize()).isEqualTo(1);
        assertThat(result.nextArticleId()).isEqualTo(ARTICLE_ID);
        ArticleDoc stored = articleRepository.findById(ARTICLE_ID).orElseThrow();
        assertThat(stored.getRevisionId()).isEqualTo(revisionId);
        assertThat(stored.getContentHash()).isEqualTo(hash);
        assertThat(stored.getTitle()).isEqualTo("backfilled-without-event");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM consumer_inbox
                WHERE consumer_name='article-search-current-pointer'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void reconcileCannotWriteWhileRealtimeProjectionOwnsTheAggregateLease() {
        String hash = "7".repeat(64);
        long revisionId = seedPublishedArticle("fenced-current-pointer", hash);
        DomainEvent inFlightRealtime = event(1L, DomainEventType.ARTICLE_REVISION_PUBLISHED,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID));
        ProjectionLease lease = projectionLeaseService.acquire(
                "article-search-current-pointer", inFlightRealtime, Duration.ofSeconds(30));
        assertThat(lease.acquired()).isTrue();

        ArticleDoc realtimeEffect = new ArticleDoc();
        realtimeEffect.setId(ARTICLE_ID);
        realtimeEffect.setTitle("realtime-effect-must-not-be-overwritten");
        realtimeEffect.setContent("published-body");
        realtimeEffect.setRevisionId(revisionId);
        realtimeEffect.setContentHash(hash);
        articleRepository.save(realtimeEffect);

        ArticleSearchProjectionReconciler.BatchResult result =
                projectionReconciler.reconcileAfter(ARTICLE_ID - 1, 1);

        assertThat(result.busy()).isEqualTo(1);
        assertThat(result.upserted()).isZero();
        assertThat(result.deleted()).isZero();
        assertThat(articleRepository.findById(ARTICLE_ID).orElseThrow().getTitle())
                .isEqualTo("realtime-effect-must-not-be-overwritten");
    }

    @Test
    void cutoverIgnoresLegacyNakedArticleIdEsMessages() {
        String hash = "8".repeat(64);
        long revisionId = seedPublishedArticle("legacy-mutable-title", hash);
        ArticleDoc pointerOwned = new ArticleDoc();
        pointerOwned.setId(ARTICLE_ID);
        pointerOwned.setTitle("pointer-owned-document");
        pointerOwned.setContent("published-body");
        pointerOwned.setRevisionId(revisionId);
        pointerOwned.setContentHash(hash);
        articleRepository.save(pointerOwned);

        legacyEsSyncConsumer.handleSyncMessage(ARTICLE_ID);

        ArticleDoc stored = articleRepository.findById(ARTICLE_ID).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("pointer-owned-document");
        assertThat(stored.getRevisionId()).isEqualTo(revisionId);
        assertThat(stored.getContentHash()).isEqualTo(hash);
    }

    @Test
    void replacementThenRejectedRevisionKeepsTheOldPublicDocumentAndDropsLateVersions() {
        long firstRevision = seedPublishedArticle("first-public", "d".repeat(64));
        DomainEvent publishV1 = event(1L, DomainEventType.ARTICLE_REVISION_PUBLISHED,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID));
        sendSearch(publishV1);
        awaitDocument(firstRevision, "d".repeat(64), "first-public");

        long replacement = insertRevision(2L, "replacement-public", "e".repeat(64));
        jdbcTemplate.update("""
                UPDATE article SET latest_revision_id=?,published_revision_id=?,lock_version=2,
                    title='legacy-must-not-win',content='legacy-must-not-win' WHERE id=?
                """, replacement, replacement, ARTICLE_ID);
        DomainEvent publishV2 = event(2L, DomainEventType.ARTICLE_REVISION_PUBLISHED,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID));
        sendSearch(publishV2);
        awaitDocument(replacement, "e".repeat(64), "replacement-public");

        long rejected = insertRevision(3L, "rejected-private", "f".repeat(64));
        jdbcTemplate.update("UPDATE article SET latest_revision_id=?,pending_revision_id=NULL,lock_version=3 WHERE id=?",
                rejected, ARTICLE_ID);
        DomainEvent rejectV3 = event(3L, DomainEventType.ARTICLE_REVISION_REJECTED,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID));
        sendSearch(rejectV3);
        sendSearch(rejectV3); // duplicate delivery is safe
        sendSearch(event(2L, DomainEventType.ARTICLE_REVISION_PUBLISHED,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID))); // late event

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ArticleDoc stored = articleRepository.findById(ARTICLE_ID).orElseThrow();
            assertThat(stored.getRevisionId()).isEqualTo(replacement);
            assertThat(stored.getTitle()).isEqualTo("replacement-public");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT last_applied_version FROM projection_watermark
                    WHERE consumer_name='article-search-current-pointer' AND aggregate_id=?
                    """, Long.class, ARTICLE_ID)).isEqualTo(3L);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM consumer_inbox
                    WHERE consumer_name='article-search-current-pointer'
                    """, Long.class)).isEqualTo(3L);
        });
    }

    @Test
    void sameLifecycleDeleteV5RestoreV6ClearsTombstoneAndDelayedDeletesCannotReviveIt() {
        long revisionId = seedPublishedArticle("restorable", "1".repeat(64));
        jdbcTemplate.update("""
                UPDATE article SET is_deleted=1,visibility_state='RECYCLED',lock_version=5 WHERE id=?
                """, ARTICLE_ID);
        sendSearch(event(5L, DomainEventType.ARTICLE_DELETED,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID).put("transition", "RECYCLED")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEsTombstone(5L);
            assertWatermark(5L, true);
        });

        jdbcTemplate.update("""
                UPDATE article SET is_deleted=0,visibility_state='PUBLIC',lock_version=6 WHERE id=?
                """, ARTICLE_ID);
        sendSearch(event(6L, DomainEventType.ARTICLE_REVISION_PUBLISHED,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID).put("transition", "RESTORED")));
        awaitDocument(revisionId, "1".repeat(64), "restorable");
        assertWatermark(6L, false);

        sendSearch(event(5L, DomainEventType.ARTICLE_DELETED,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID)));
        sendSearch(event(4L, DomainEventType.ARTICLE_DELETED,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID)));
        sendSearch(new DomainEvent(UUID.randomUUID(), "ARTICLE", ARTICLE_ID, 99L, 0L,
                DomainEventType.ARTICLE_DELETED, 1,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID), Instant.now()));
        await().during(Duration.ofMillis(750)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(articleRepository.findById(ARTICLE_ID)).isPresent();
            assertWatermark(6L, false);
        });
    }

    @Test
    void routedRestoreAndReportEventsAreNotificationNoOpsButStillEnterInbox() {
        long publishedRevisionId = seedPublishedArticle("no-op", "2".repeat(64));
        DomainEvent restored = event(6L, DomainEventType.ARTICLE_REVISION_PUBLISHED,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID)
                        .put("transition", "RESTORED")
                        .put("publishedRevisionId", publishedRevisionId));
        DomainEvent reported = event(7L, DomainEventType.ARTICLE_REVISION_REJECTED,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID)
                        .put("transition", "REPORT_CONFIRMED")
                        .put("revisionId", publishedRevisionId)
                        .putNull("oldPublishedRevisionId")
                        .putNull("newPublishedRevisionId"));
        sendRouted(restored);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(articleRepository.findById(ARTICLE_ID)).isPresent();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM consumer_inbox
                    WHERE consumer_name IN ('article-search-current-pointer',
                                             'article-moderation-notification')
                      AND event_id=UNHEX(REPLACE(?, '-', ''))
                    """, Long.class, restored.eventId().toString())).isEqualTo(2L);
        });
        jdbcTemplate.update("""
                UPDATE article SET latest_revision_id=?,published_revision_id=NULL,
                    status=3,visibility_state='PRIVATE',review_state='REJECTED',lock_version=7
                WHERE id=?
                """, publishedRevisionId, ARTICLE_ID);
        sendRouted(reported);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEsTombstone(7L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM message WHERE target_id=? AND type=4",
                    Long.class, ARTICLE_ID)).isZero();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM consumer_inbox
                    WHERE consumer_name='article-moderation-notification'
                    """, Long.class)).isEqualTo(2L);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM consumer_inbox
                    WHERE consumer_name='article-search-current-pointer'
                    """, Long.class)).isEqualTo(2L);
        });
    }

    private void deleteIndexIfPresent(String index) throws Exception {
        try {
            elasticsearchRestClient.performRequest(new Request("DELETE", "/" + index));
        } catch (org.elasticsearch.client.ResponseException exception) {
            if (exception.getResponse().getStatusLine().getStatusCode() != 404) {
                throw exception;
            }
        }
    }

    private long seedPublishedArticle(String title, String contentHash) {
        jdbcTemplate.update("""
                INSERT INTO article(id,title,content,summary,cover,author_id,view_count,like_count,
                                    comment_count,collect_count,create_time,update_time,status,
                                    visibility_state,review_state,lifecycle_epoch,lock_version,is_deleted)
                VALUES(?,?,?,'summary','cover',?,1,2,3,4,NOW(6),NOW(6),1,
                       'PUBLIC','APPROVED',1,1,0)
                """, ARTICLE_ID, title, "legacy-body", AUTHOR_ID);
        jdbcTemplate.update("""
                INSERT INTO article_revision(article_id,revision_no,title,summary,body_markdown,
                                             body_plain,cover,tags_json,content_hash,
                                             source_draft_version,created_by,created_at)
                VALUES(?,1,?,'summary','published-body','published-body','cover',JSON_ARRAY(),?,
                       1,?,NOW(6))
                """, ARTICLE_ID, title, contentHash, AUTHOR_ID);
        long revisionId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_revision WHERE article_id=? AND revision_no=1",
                Long.class, ARTICLE_ID);
        jdbcTemplate.update("""
                UPDATE article SET latest_revision_id=?,published_revision_id=? WHERE id=?
                """, revisionId, revisionId, ARTICLE_ID);
        return revisionId;
    }

    private long insertRevision(long revisionNo, String title, String hash) {
        jdbcTemplate.update("""
                INSERT INTO article_revision(article_id,revision_no,title,summary,body_markdown,
                                             body_plain,cover,tags_json,content_hash,
                                             source_draft_version,created_by,created_at)
                VALUES(?,? ,?,'summary','published-body','published-body','cover',JSON_ARRAY(),?,
                       1,?,NOW(6))
                """, ARTICLE_ID, revisionNo, title, hash, AUTHOR_ID);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM article_revision WHERE article_id=? AND revision_no=?",
                Long.class, ARTICLE_ID, revisionNo);
    }

    private void sendSearch(DomainEvent event) {
        rabbitTemplate.convertAndSend(RabbitConfig.ARTICLE_SEARCH_PROJECTION_QUEUE, event);
    }

    private void sendRouted(DomainEvent event) {
        rabbitTemplate.convertAndSend(RabbitConfig.DOMAIN_EVENT_EXCHANGE,
                event.eventType().routingKey(), event);
    }

    private void awaitDocument(long revisionId, String hash, String title) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var result = articleRepository.findById(ARTICLE_ID);
            assertThat(result).isPresent();
            ArticleDoc stored = result.orElseThrow();
            assertThat(stored.getRevisionId()).isEqualTo(revisionId);
            assertThat(stored.getContentHash()).isEqualTo(hash);
            assertThat(stored.getTitle()).isEqualTo(title);
            assertThat(stored.getProjectionLifecycleEpoch()).isEqualTo(1L);
            assertThat(stored.getProjectionVersion()).isNotNull();
            assertThat(stored.getProjectionTombstone()).isFalse();
        });
    }

    private void assertEsTombstone(long version) throws Exception {
        Request get = new Request("GET", "/article/_doc/" + ARTICLE_ID);
        org.elasticsearch.client.Response response;
        try {
            response = elasticsearchRestClient.performRequest(get);
        } catch (org.elasticsearch.client.ResponseException exception) {
            if (exception.getResponse().getStatusLine().getStatusCode() == 404) {
                org.assertj.core.api.Assertions.fail("projection tombstone is not visible yet");
            }
            throw exception;
        }
        var source = objectMapper.readTree(EntityUtils.toString(response.getEntity())).path("_source");
        assertThat(source.path("projectionTombstone").asBoolean()).isTrue();
        assertThat(source.path("projectionLifecycleEpoch").asLong()).isEqualTo(1L);
        assertThat(source.path("projectionVersion").asLong()).isEqualTo(version);
        assertThat(source.has("revisionId")).isFalse();
        assertThat(source.has("contentHash")).isFalse();
    }

    private void assertWatermark(long version, boolean tombstone) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT last_applied_version,lifecycle_epoch,tombstone
                FROM projection_watermark
                WHERE consumer_name='article-search-current-pointer' AND aggregate_type='ARTICLE'
                  AND aggregate_id=?
                """, ARTICLE_ID))
                .containsEntry("last_applied_version", version)
                .containsEntry("lifecycle_epoch", 1L)
                .containsEntry("tombstone", tombstone);
    }

    private DomainEvent event(long version, DomainEventType type,
                              com.fasterxml.jackson.databind.JsonNode payload) {
        return new DomainEvent(UUID.randomUUID(), "ARTICLE", ARTICLE_ID, version, 1L,
                type, 1, payload, Instant.now());
    }
}
