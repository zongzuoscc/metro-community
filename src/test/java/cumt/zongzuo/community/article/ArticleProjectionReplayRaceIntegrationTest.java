package cumt.zongzuo.community.article;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.article.projection.ArticleProjectionSource;
import cumt.zongzuo.community.article.projection.ArticleSearchProjectionConsumer;
import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.document.ArticleDoc;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.projection.ProjectionLeaseService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@TestPropertySource(properties = {
        "metro.article.revision-mode=CUTOVER",
        "metro.article.projection.lease-duration=PT1S",
        "metro.article.projection.retry.initial-interval=PT0.25S",
        "metro.article.projection.retry.max-interval=PT0.25S",
        "metro.article.projection.retry.max-attempts=8"
})
class ArticleProjectionReplayRaceIntegrationTest extends IntegrationTestSupport {

    private static final String SEARCH_CONSUMER = "article-search-current-pointer";
    private static final String NOTIFICATION_CONSUMER = "article-moderation-notification";
    private static final long AUTHOR_ID = 96_200L;
    private static final long REVIEWER_ID = 96_201L;
    private static final long ARTICLE_ID = 96_300L;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private ProjectionLeaseService leaseService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private RestClient elasticsearchRestClient;
    @Autowired
    private ArticleSearchProjectionConsumer searchProjectionConsumer;
    @MockitoSpyBean
    private ArticleProjectionSource projectionSource;

    @BeforeAll
    void startNamedListeners() {
        listenerRegistry.getListenerContainer("articleSearchProjectionConsumer").start();
        listenerRegistry.getListenerContainer("articleModerationNotificationConsumer").start();
    }

    @AfterAll
    void stopNamedListeners() {
        listenerRegistry.getListenerContainer("articleSearchProjectionConsumer").stop();
        listenerRegistry.getListenerContainer("articleModerationNotificationConsumer").stop();
    }

    @BeforeEach
    void resetFixture() {
        dropFailureTrigger();
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS projection_failure_probe(
                  consumer_name VARCHAR(96) PRIMARY KEY,
                  attempts INT NOT NULL
                ) ENGINE=MyISAM
                """);
        jdbcTemplate.update("DELETE FROM projection_failure_probe");
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(RabbitConfig.ARTICLE_SEARCH_PROJECTION_QUEUE);
            channel.queuePurge(RabbitConfig.ARTICLE_SEARCH_PROJECTION_QUEUE + ".dlq");
            channel.queuePurge(RabbitConfig.ARTICLE_MODERATION_NOTIFICATION_QUEUE);
            channel.queuePurge(RabbitConfig.ARTICLE_MODERATION_NOTIFICATION_QUEUE + ".dlq");
            return null;
        });
        articleRepository.deleteById(ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM consumer_inbox WHERE consumer_name IN (?,?)",
                SEARCH_CONSUMER, NOTIFICATION_CONSUMER);
        jdbcTemplate.update("DELETE FROM projection_watermark WHERE consumer_name=?", SEARCH_CONSUMER);
        jdbcTemplate.update("DELETE FROM message WHERE target_id=? AND type=4", ARTICLE_ID);
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL WHERE id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_moderation_job WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_draft WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article WHERE id=?", ARTICLE_ID);
        ensureUser(AUTHOR_ID, "replay-author", 0);
        ensureUser(REVIEWER_ID, "replay-reviewer", 1);
    }

    @AfterEach
    void removeFailureTrigger() {
        dropFailureTrigger();
        reset(projectionSource);
    }

    @Test
    void busyDeliveryIsNotAcknowledgedAndDedicatedRetryOutlivesTheLease() {
        long revisionId = seedPublished("busy-recovered", "3".repeat(64));
        DomainEvent blocker = event(UUID.randomUUID(), 1L,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID));
        leaseService.acquire(SEARCH_CONSUMER, blocker, Duration.ofSeconds(1));
        DomainEvent delivery = event(UUID.randomUUID(), 2L,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID));

        Instant sentAt = Instant.now();
        sendSearch(delivery);

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(inboxCount(SEARCH_CONSUMER, delivery.eventId())).isEqualTo(1L);
            assertThat(articleRepository.findById(ARTICLE_ID))
                    .get().extracting(ArticleDoc::getRevisionId).isEqualTo(revisionId);
        });
        assertThat(Duration.between(sentAt, Instant.now())).isGreaterThan(Duration.ofMillis(750));
        assertThat(rabbitTemplate.receive(RabbitConfig.ARTICLE_SEARCH_PROJECTION_QUEUE + ".dlq", 100))
                .isNull();
    }

    @Test
    void esSuccessThenInboxFailureReplaysIdempotentEffectAfterLeaseExpiry() throws Exception {
        long revisionId = seedPublished("effect-replayed", "4".repeat(64));
        installInboxFailureTrigger(SEARCH_CONSUMER);
        DomainEvent delivery = event(UUID.randomUUID(), 3L,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID));

        sendSearch(delivery);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(articleRepository.findById(ARTICLE_ID))
                    .get().extracting(ArticleDoc::getRevisionId).isEqualTo(revisionId);
            assertThat(probeAttempts(SEARCH_CONSUMER)).isGreaterThanOrEqualTo(1);
            assertThat(inboxCount(SEARCH_CONSUMER, delivery.eventId())).isZero();
        });

        dropFailureTrigger();
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                assertThat(inboxCount(SEARCH_CONSUMER, delivery.eventId())).isEqualTo(1L));

        Request get = new Request("GET", "/article/_doc/" + ARTICLE_ID);
        long elasticsearchVersion = objectMapper.readTree(EntityUtils.toString(
                elasticsearchRestClient.performRequest(get).getEntity())).path("_version").longValue();
        assertThat(elasticsearchVersion).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void inFlightOldEffectCannotResurrectANewerTombstoneAfterItsLeaseExpires() throws Exception {
        seedPublished("stale-in-flight", "9".repeat(64));
        jdbcTemplate.update("UPDATE article SET lock_version=5 WHERE id=?", ARTICLE_ID);
        CountDownLatch oldSnapshotRead = new CountDownLatch(1);
        CountDownLatch releaseOldEffect = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        doAnswer(invocation -> {
            ArticleProjectionSource.Snapshot snapshot =
                    (ArticleProjectionSource.Snapshot) invocation.callRealMethod();
            if (firstRead.compareAndSet(true, false)) {
                oldSnapshotRead.countDown();
                if (!releaseOldEffect.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release the old projection effect");
                }
            }
            return snapshot;
        }).when(projectionSource).loadCurrent(ARTICLE_ID, 1L, 5L);

        DomainEvent oldV5 = event(UUID.randomUUID(), 5L,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID));
        CompletableFuture<Throwable> oldAttempt = CompletableFuture.supplyAsync(() -> {
            try {
                searchProjectionConsumer.consume(oldV5);
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        });
        assertThat(oldSnapshotRead.await(3, TimeUnit.SECONDS)).isTrue();

        jdbcTemplate.update("""
                UPDATE article SET is_deleted=1,visibility_state='RECYCLED',lock_version=6
                WHERE id=?
                """, ARTICLE_ID);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT lease_until <= NOW(6) FROM projection_watermark
                        WHERE consumer_name=? AND aggregate_type='ARTICLE' AND aggregate_id=?
                        """, Boolean.class, SEARCH_CONSUMER, ARTICLE_ID)).isTrue());
        DomainEvent tombstoneV6 = new DomainEvent(UUID.randomUUID(), "ARTICLE", ARTICLE_ID,
                6L, 1L, DomainEventType.ARTICLE_DELETED, 1,
                objectMapper.createObjectNode().put("articleId", ARTICLE_ID), Instant.now());
        searchProjectionConsumer.consume(tombstoneV6);
        releaseOldEffect.countDown();

        assertThat(oldAttempt.get(3, TimeUnit.SECONDS)).isInstanceOf(IllegalStateException.class);
        Request get = new Request("GET", "/article/_doc/" + ARTICLE_ID);
        var source = objectMapper.readTree(EntityUtils.toString(
                elasticsearchRestClient.performRequest(get).getEntity())).path("_source");
        assertThat(source.path("projectionTombstone").asBoolean()).isTrue();
        assertThat(source.path("projectionLifecycleEpoch").asLong()).isEqualTo(1L);
        assertThat(source.path("projectionVersion").asLong()).isEqualTo(6L);
    }

    @Test
    void impossiblePublishedRestoreWithoutAPublishedRevisionFailsClosed() {
        DomainEvent poison = event(UUID.randomUUID(), 7L, objectMapper.createObjectNode()
                .put("articleId", ARTICLE_ID)
                .put("transition", "RESTORED")
                .putNull("publishedRevisionId"));

        sendNotification(poison);

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                assertThat(queueDepth(RabbitConfig.ARTICLE_MODERATION_NOTIFICATION_QUEUE + ".dlq"))
                        .isEqualTo(1L));
        assertThat(inboxCount(NOTIFICATION_CONSUMER, poison.eventId())).isZero();
        assertThat(messageCount(poison.eventId())).isZero();
    }

    @Test
    void messageAndInboxRollbackTogetherThenDuplicateDeliveryCommitsExactlyOnce() {
        String hash = "5".repeat(64);
        long revisionId = seedPublished("transactional-notification", hash);
        jdbcTemplate.update("""
                INSERT INTO article_moderation_job(article_id,revision_id,content_hash,state,
                    attempt_count,reviewer_id,review_reason,reviewed_at,created_at,updated_at,lock_version)
                VALUES(?,?,?,'HUMAN_APPROVED',0,?,'approved',NOW(6),NOW(6),NOW(6),1)
                """, ARTICLE_ID, revisionId, hash, REVIEWER_ID);
        long jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_moderation_job WHERE article_id=?", Long.class, ARTICLE_ID);
        installInboxFailureTrigger(NOTIFICATION_CONSUMER);
        DomainEvent event = event(UUID.randomUUID(), 4L, objectMapper.createObjectNode()
                .put("articleId", ARTICLE_ID).put("revisionId", revisionId)
                .put("moderationJobId", jobId).put("contentHash", hash));

        sendNotification(event);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(probeAttempts(NOTIFICATION_CONSUMER)).isGreaterThanOrEqualTo(1));
        assertThat(messageCount(event.eventId())).isZero();
        assertThat(inboxCount(NOTIFICATION_CONSUMER, event.eventId())).isZero();

        dropFailureTrigger();
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(messageCount(event.eventId())).isEqualTo(1L);
            assertThat(inboxCount(NOTIFICATION_CONSUMER, event.eventId())).isEqualTo(1L);
        });
        sendNotification(event);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(messageCount(event.eventId())).isEqualTo(1L);
            assertThat(inboxCount(NOTIFICATION_CONSUMER, event.eventId())).isEqualTo(1L);
        });
    }

    @Test
    void tupleShapedEventWithMismatchedFrozenIdentityFailsClosedWithoutInboxAck() {
        String hash = "6".repeat(64);
        long revisionId = seedPublished("mismatch-must-not-ack", hash);
        jdbcTemplate.update("""
                INSERT INTO article_moderation_job(article_id,revision_id,content_hash,state,
                    attempt_count,reviewer_id,review_reason,reviewed_at,created_at,updated_at,lock_version)
                VALUES(?,?,?,'HUMAN_APPROVED',0,?,'approved',NOW(6),NOW(6),NOW(6),1)
                """, ARTICLE_ID, revisionId, hash, REVIEWER_ID);
        long jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_moderation_job WHERE article_id=?", Long.class, ARTICLE_ID);
        DomainEvent malformed = event(UUID.randomUUID(), 5L, objectMapper.createObjectNode()
                .put("articleId", ARTICLE_ID).put("revisionId", revisionId)
                .put("moderationJobId", jobId).put("contentHash", "7".repeat(64)));

        sendNotification(malformed);

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                assertThat(queueDepth(RabbitConfig.ARTICLE_MODERATION_NOTIFICATION_QUEUE + ".dlq"))
                        .isEqualTo(1L));
        assertThat(messageCount(malformed.eventId())).isZero();
        assertThat(inboxCount(NOTIFICATION_CONSUMER, malformed.eventId())).isZero();
    }

    @Test
    void maximumReviewReasonIsUnicodeSafelyBoundedToMessageColumn() {
        String hash = "8".repeat(64);
        long revisionId = seedPublished("T".repeat(100), hash);
        String reason = "😺".repeat(500);
        jdbcTemplate.update("""
                INSERT INTO article_moderation_job(article_id,revision_id,content_hash,state,
                    attempt_count,reviewer_id,review_reason,reviewed_at,created_at,updated_at,lock_version)
                VALUES(?,?,?,'HUMAN_REJECTED',0,?,?,NOW(6),NOW(6),NOW(6),1)
                """, ARTICLE_ID, revisionId, hash, REVIEWER_ID, reason);
        long jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_moderation_job WHERE article_id=?", Long.class, ARTICLE_ID);
        DomainEvent rejected = new DomainEvent(UUID.randomUUID(), "ARTICLE", ARTICLE_ID, 6L, 1L,
                DomainEventType.ARTICLE_REVISION_REJECTED, 1, objectMapper.createObjectNode()
                .put("articleId", ARTICLE_ID).put("revisionId", revisionId)
                .put("moderationJobId", jobId).put("contentHash", hash), Instant.now());

        sendNotification(rejected);

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                assertThat(messageCount(rejected.eventId())).isEqualTo(1L));
        String stored = jdbcTemplate.queryForObject("""
                SELECT content FROM message WHERE source_event_id=UNHEX(REPLACE(?, '-', ''))
                """, String.class, rejected.eventId().toString());
        assertThat(stored.codePointCount(0, stored.length())).isLessThanOrEqualTo(500);
        assertThat(stored).doesNotContain("\ufffd");
        assertThat(inboxCount(NOTIFICATION_CONSUMER, rejected.eventId())).isEqualTo(1L);
    }

    private long seedPublished(String title, String hash) {
        jdbcTemplate.update("""
                INSERT INTO article(id,title,content,summary,cover,author_id,view_count,like_count,
                    comment_count,collect_count,create_time,update_time,status,visibility_state,
                    review_state,lifecycle_epoch,lock_version,is_deleted)
                VALUES(?,?,'legacy','summary','cover',?,0,0,0,0,NOW(6),NOW(6),1,
                    'PUBLIC','APPROVED',1,1,0)
                """, ARTICLE_ID, title, AUTHOR_ID);
        jdbcTemplate.update("""
                INSERT INTO article_revision(article_id,revision_no,title,summary,body_markdown,
                    body_plain,cover,tags_json,content_hash,source_draft_version,created_by,created_at)
                VALUES(?,1,?,'summary','body','body','cover',JSON_ARRAY(),?,1,?,NOW(6))
                """, ARTICLE_ID, title, hash, AUTHOR_ID);
        long revisionId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_revision WHERE article_id=?", Long.class, ARTICLE_ID);
        jdbcTemplate.update("UPDATE article SET latest_revision_id=?,published_revision_id=? WHERE id=?",
                revisionId, revisionId, ARTICLE_ID);
        return revisionId;
    }

    private void installInboxFailureTrigger(String consumer) {
        dropFailureTrigger();
        executeAsRoot("""
                CREATE TRIGGER fail_projection_inbox BEFORE INSERT ON consumer_inbox
                FOR EACH ROW
                BEGIN
                  IF NEW.consumer_name = '%s' THEN
                    INSERT INTO projection_failure_probe(consumer_name,attempts) VALUES(NEW.consumer_name,1)
                      ON DUPLICATE KEY UPDATE attempts=attempts+1;
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SIMULATED_INBOX_FAILURE';
                  END IF;
                END
                """.formatted(consumer));
    }

    private void dropFailureTrigger() {
        executeAsRoot("DROP TRIGGER IF EXISTS fail_projection_inbox");
    }

    private void executeAsRoot(String sql) {
        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("test root DDL failed", exception);
        }
    }

    private int probeAttempts(String consumer) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(attempts),0) FROM projection_failure_probe WHERE consumer_name=?",
                Integer.class, consumer);
        return value == null ? 0 : value;
    }

    private long messageCount(UUID eventId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM message WHERE source_event_id=UNHEX(REPLACE(?, '-', ''))
                """, Long.class, eventId.toString());
    }

    private long inboxCount(String consumer, UUID eventId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM consumer_inbox
                WHERE consumer_name=? AND event_id=UNHEX(REPLACE(?, '-', ''))
                """, Long.class, consumer, eventId.toString());
    }

    private long queueDepth(String queue) {
        Long depth = rabbitTemplate.execute(channel -> channel.messageCount(queue));
        return depth == null ? 0L : depth;
    }

    private void ensureUser(long id, String name, int role) {
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status,deleted)
                VALUES(?,?, 'unused', CONCAT(?, '@example.com'), ?,0,0)
                ON DUPLICATE KEY UPDATE status=0,deleted=0
                """, id, name, name, role);
    }

    private DomainEvent event(UUID id, long version, com.fasterxml.jackson.databind.JsonNode payload) {
        return new DomainEvent(id, "ARTICLE", ARTICLE_ID, version, 1L,
                DomainEventType.ARTICLE_REVISION_PUBLISHED, 1, payload, Instant.now());
    }

    private void sendSearch(DomainEvent event) {
        rabbitTemplate.convertAndSend(RabbitConfig.ARTICLE_SEARCH_PROJECTION_QUEUE, event);
    }

    private void sendNotification(DomainEvent event) {
        rabbitTemplate.convertAndSend(RabbitConfig.ARTICLE_MODERATION_NOTIFICATION_QUEUE, event);
    }
}
