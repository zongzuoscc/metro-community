package cumt.zongzuo.community.recommendation;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import cumt.zongzuo.community.recommendation.entity.UserArticleEvent;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventType;
import cumt.zongzuo.community.recommendation.mq.RecommendationEventConsumer;
import cumt.zongzuo.community.recommendation.service.RecommendationFactPersistenceService;
import cumt.zongzuo.community.recommendation.service.RecommendationMetricsService;
import cumt.zongzuo.community.recommendation.service.RecommendationProfileService;
import cumt.zongzuo.community.recommendation.service.RecommendationProfileRecoveryService;
import cumt.zongzuo.community.recommendation.task.RecommendationOutboxDispatcher;
import cumt.zongzuo.community.recommendation.task.RecommendationProfileRepairTask;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class RecommendationEventIntegrationTest extends IntegrationTestSupport {

    private static final long USER_ID = 1001L;
    private static final long ARTICLE_ID = 2001L;
    private static final long AUTHOR_ID = 3001L;
    private static final long FOLLOWED_AUTHOR_ID = 3002L;
    private static final long TAG_ID = 4001L;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired
    private RecommendationProfileService profileService;
    @Autowired
    private RecommendationEventConsumer consumer;
    @Autowired
    private RecommendationMetricsService metricsService;
    @Autowired
    private RecommendationProperties properties;
    @Autowired
    private RecommendationProfileRecoveryService profileRecoveryService;
    @Autowired
    private RecommendationProfileRepairTask profileRepairTask;
    @Autowired
    private RecommendationFactPersistenceService factPersistenceService;
    @Autowired
    private Clock clock;

    @BeforeAll
    void startRecommendationListener() {
        listenerRegistry.getListenerContainer("recommendationEventConsumer").start();
    }

    @AfterAll
    void stopRecommendationListener() {
        listenerRegistry.getListenerContainer("recommendationEventConsumer").stop();
    }

    @BeforeEach
    void cleanAndSeed() {
        properties.setProfileFactLimit(10_000);
        properties.setProfileTagAssociationLimit(50_000);
        properties.setProfileMaxTags(100);
        properties.setProfileMaxAuthors(100);
        properties.setProfileRepairEnabled(true);
        properties.setProfileRepairBatchSize(100);
        jdbcTemplate.update("DELETE FROM recommendation_profile_checkpoint");
        amqpAdmin.purgeQueue(RecommendationOutboxDispatcher.EVENT_QUEUE, true);
        jdbcTemplate.update("DELETE FROM user_article_event");
        jdbcTemplate.update("DELETE FROM article_tag");
        jdbcTemplate.update("DELETE FROM tag");
        jdbcTemplate.update("DELETE FROM article");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        jdbcTemplate.update("""
                INSERT INTO article
                    (id, title, summary, content, author_id, status, is_deleted, create_time, update_time)
                VALUES (?, 'Redis guide', 'Redis', 'Redis', ?, 1, 0, NOW(), NOW())
                """, ARTICLE_ID, AUTHOR_ID);
        jdbcTemplate.update("INSERT INTO tag (id, name, article_count, create_time) VALUES (?, 'redis', 1, NOW())",
                TAG_ID);
        jdbcTemplate.update("INSERT INTO article_tag (article_id, tag_id) VALUES (?, ?)", ARTICLE_ID, TAG_ID);
    }

    @Test
    void duplicateRabbitDeliveryCreatesOneFactAndAddsProfileOnce() {
        RecommendationEventCommand event = event(
                RecommendationEventType.VIEW, ARTICLE_ID, null, "view:1001:2001:2026-08-09");

        rabbitTemplate.convertAndSend(RecommendationOutboxDispatcher.EVENT_QUEUE, event);
        rabbitTemplate.convertAndSend(RecommendationOutboxDispatcher.EVENT_QUEUE, event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(eventCount()).isEqualTo(1);
            assertThat(profileService.profileTags(USER_ID, 5)).containsEntry("redis", 1D);
            assertThat(profileService.profileAuthors(USER_ID, 5)).containsEntry(AUTHOR_ID, 1D);
            assertThat(redisTemplate.opsForValue().get(eventMetricKey(event))).isEqualTo("1");
            assertThat(redisTemplate.getExpire(eventMetricKey(event))).isPositive()
                    .isLessThanOrEqualTo(Duration.ofDays(40).toSeconds());
        });
    }

    @Test
    void concurrentDuplicateInsertionCreatesOneFactAndOneEventMetric() throws Exception {
        RecommendationEventCommand event = event(
                RecommendationEventType.LIKE, ARTICLE_ID, null, "like:concurrent-duplicate");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Void> consume = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                consumer.consume(event);
                return null;
            };
            Future<Void> first = executor.submit(consume);
            Future<Void> second = executor.submit(consume);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(eventCount()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(eventMetricKey(event))).isEqualTo("1");
    }

    @Test
    void realMetricsRedisFailureStillPersistsFactAndRebuildsHealthyProfile() throws Exception {
        RecommendationEventCommand event = event(
                RecommendationEventType.COLLECT, ARTICLE_ID, null, "collect:metrics-redis-failure");
        LettuceConnectionFactory failingFactory = failingRedisFactory(reserveThenClosePort());
        try {
            RecommendationMetricsService failingMetrics = new RecommendationMetricsService(
                    new StringRedisTemplate(failingFactory), clock);
            RecommendationEventConsumer isolatedConsumer = new RecommendationEventConsumer(
                    factPersistenceService, profileService, failingMetrics, profileRecoveryService);

            assertThatCode(() -> isolatedConsumer.consume(event)).doesNotThrowAnyException();

            assertThat(eventCount()).isEqualTo(1);
            assertThat(profileService.profileTags(USER_ID, 5)).containsEntry("redis", 8D);
            assertThat(profileService.profileAuthors(USER_ID, 5)).containsEntry(AUTHOR_ID, 8D);
            assertThat(redisTemplate.opsForValue().get(eventMetricKey(event))).isNull();
        } finally {
            failingFactory.destroy();
        }
    }

    @Test
    void realProfileRedisFailureStillPropagatesAfterFactAndBestEffortMetric() throws Exception {
        RecommendationEventCommand event = event(
                RecommendationEventType.COMMENT, ARTICLE_ID, null, "comment:profile-redis-failure");
        LettuceConnectionFactory failingFactory = failingRedisFactory(reserveThenClosePort());
        try {
            RecommendationProfileService failingProfile = new RecommendationProfileService(
                    jdbcTemplate, new StringRedisTemplate(failingFactory),
                    new cumt.zongzuo.community.recommendation.config.RecommendationProperties());
            RecommendationEventConsumer isolatedConsumer = new RecommendationEventConsumer(
                    factPersistenceService, failingProfile, metricsService, profileRecoveryService);

            assertThatThrownBy(() -> isolatedConsumer.consume(event)).isInstanceOf(DataAccessException.class);

            assertThat(eventCount()).isEqualTo(1);
            assertThat(redisTemplate.opsForValue().get(eventMetricKey(event))).isEqualTo("1");
        } finally {
            failingFactory.destroy();
        }
    }

    @Test
    void duplicateDeliveryRebuildsProfileAfterRedisWasLost() {
        RecommendationEventCommand event = event(
                RecommendationEventType.COLLECT, ARTICLE_ID, null, "collect:recovery");
        insertEvent(event);
        redisTemplate.opsForZSet().add(tagKey(), "corrupt", 99D);
        redisTemplate.opsForZSet().add(authorKey(), "9999", 99D);

        rabbitTemplate.convertAndSend(RecommendationOutboxDispatcher.EVENT_QUEUE, event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(eventCount()).isEqualTo(1);
            assertThat(profileService.profileTags(USER_ID, 5))
                    .containsExactly(Map.entry("redis", 8D));
            assertThat(profileService.profileAuthors(USER_ID, 5))
                    .containsExactly(Map.entry(AUTHOR_ID, 8D));
        });
    }

    @Test
    void failedProfileDeliveryLeavesDurableStaleCheckpointForAutomaticRepairWithoutAnotherEvent()
            throws Exception {
        RecommendationEventCommand event = event(
                RecommendationEventType.COLLECT, ARTICLE_ID, null, "collect:durable-profile-repair");
        LettuceConnectionFactory failingFactory = failingRedisFactory(reserveThenClosePort());
        try {
            RecommendationProfileService failingProfile = new RecommendationProfileService(
                    jdbcTemplate, new StringRedisTemplate(failingFactory), properties);
            RecommendationEventConsumer isolatedConsumer = new RecommendationEventConsumer(
                    factPersistenceService, failingProfile, metricsService, profileRecoveryService);

            assertThatThrownBy(() -> isolatedConsumer.consume(event)).isInstanceOf(DataAccessException.class);

            Map<String, Object> stale = jdbcTemplate.queryForMap("""
                    SELECT requested_event_id,rebuilt_event_id
                    FROM recommendation_profile_checkpoint WHERE user_id=?
                    """, USER_ID);
            long factId = jdbcTemplate.queryForObject(
                    "SELECT id FROM user_article_event WHERE dedupe_key=?", Long.class, event.dedupeKey());
            assertThat(((Number) stale.get("requested_event_id")).longValue()).isEqualTo(factId);
            assertThat(((Number) stale.get("rebuilt_event_id")).longValue()).isZero();

            profileRepairTask.repairProfiles();

            assertThat(profileService.profileTags(USER_ID, 5)).containsExactly(Map.entry("redis", 8D));
            assertThat(profileService.profileAuthors(USER_ID, 5)).containsExactly(Map.entry(AUTHOR_ID, 8D));
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT requested_event_id=rebuilt_event_id
                    FROM recommendation_profile_checkpoint WHERE user_id=?
                    """, Boolean.class, USER_ID)).isTrue();
        } finally {
            failingFactory.destroy();
        }
    }

    @Test
    void factInsertRollsBackWhenItsDurableProfileCheckpointCannotBeWritten() {
        RecommendationEventCommand command = event(
                RecommendationEventType.VIEW, ARTICLE_ID, null, "view:atomic-checkpoint");
        UserArticleEvent fact = fact(command);
        jdbcTemplate.execute("DROP TABLE recommendation_profile_checkpoint");
        try {
            assertThatThrownBy(() -> factPersistenceService.persist(fact))
                    .isInstanceOf(DataAccessException.class);

            assertThat(eventCount()).isZero();
        } finally {
            createProfileCheckpointTable();
        }
    }

    @Test
    void checkpointCompletionCannotHideANewerRequestAndRepairWorkIsBatchBounded() {
        properties.setProfileRepairBatchSize(1);
        profileRecoveryService.requestRebuild(USER_ID, 10L);
        profileRecoveryService.requestRebuild(USER_ID, 11L);
        profileRecoveryService.markRebuilt(USER_ID, 10L);
        profileRecoveryService.requestRebuild(USER_ID + 1, 20L);

        assertThat(profileRecoveryService.repairDueProfiles()).isOne();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM recommendation_profile_checkpoint
                WHERE requested_event_id>rebuilt_event_id
                """, Integer.class)).isOne();
        assertThat(profileRecoveryService.repairDueProfiles()).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM recommendation_profile_checkpoint
                WHERE requested_event_id>rebuilt_event_id
                """, Integer.class)).isZero();
    }

    @Test
    void checkpointPendingFlagClearsOnlyWhenTheCurrentRequestIsFullyRebuilt() {
        profileRecoveryService.requestRebuild(USER_ID, 10L);

        assertThat(checkpointNeedsRebuild(USER_ID)).isTrue();

        profileRecoveryService.requestRebuild(USER_ID, 11L);
        profileRecoveryService.markRebuilt(USER_ID, 10L);

        assertThat(checkpointNeedsRebuild(USER_ID)).isTrue();

        profileRecoveryService.markRebuilt(USER_ID, 11L);

        assertThat(checkpointNeedsRebuild(USER_ID)).isFalse();

        profileRecoveryService.requestRebuild(USER_ID, 12L);

        assertThat(checkpointNeedsRebuild(USER_ID)).isTrue();
        assertThat(profileRecoveryService.repairDueProfiles()).isOne();
        assertThat(checkpointNeedsRebuild(USER_ID)).isFalse();

        profileRecoveryService.markRebuilt(USER_ID, 12L);
        profileRecoveryService.requestRebuild(USER_ID, 12L);

        assertThat(checkpointNeedsRebuild(USER_ID)).isTrue();
        assertThat(profileRecoveryService.repairDueProfiles()).isOne();
        assertThat(checkpointNeedsRebuild(USER_ID)).isFalse();
    }

    @Test
    void lateOlderCompletionCannotReopenAnAlreadyCompletedCheckpoint() {
        profileRecoveryService.requestRebuild(USER_ID, 10L);
        profileRecoveryService.requestRebuild(USER_ID, 11L);
        profileRecoveryService.markRebuilt(USER_ID, 11L);

        profileRecoveryService.markRebuilt(USER_ID, 10L);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT rebuilt_event_id FROM recommendation_profile_checkpoint WHERE user_id=?
                """, Long.class, USER_ID)).isEqualTo(11L);
        assertThat(checkpointNeedsRebuild(USER_ID)).isFalse();
        assertThat(profileRecoveryService.repairDueProfiles()).isZero();
    }

    @Test
    void completedCheckpointHistoryDoesNotConsumeTheBoundedPendingRepairBatch() {
        properties.setProfileRepairBatchSize(1);
        LocalDateTime old = LocalDateTime.now(clock).withNano(0).minusDays(1);
        for (long userId = 9_100L; userId < 9_110L; userId++) {
            jdbcTemplate.update("""
                    INSERT INTO recommendation_profile_checkpoint
                      (user_id,requested_event_id,rebuilt_event_id,needs_rebuild,retry_count,
                       next_attempt_at,create_time,update_time)
                    VALUES (?,?,?,0,0,?,?,?)
                    """, userId, userId, userId, old, old, old);
        }
        long pendingUser = 9_200L;
        profileRecoveryService.requestRebuild(pendingUser, 20L);

        assertThat(profileRecoveryService.repairDueProfiles()).isOne();

        assertThat(checkpointNeedsRebuild(pendingUser)).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM recommendation_profile_checkpoint WHERE needs_rebuild=1
                """, Integer.class)).isZero();
    }

    @Test
    void rebuildUsesOnlyRecentMySqlFactsAndAtomicallyReplacesBothProfiles() {
        insertEvent(event(RecommendationEventType.COLLECT, ARTICLE_ID, null, "collect:recent"));
        insertEvent(new RecommendationEventCommand(USER_ID, ARTICLE_ID, null, RecommendationEventType.LIKE,
                LocalDateTime.now().minusDays(31).withNano(0), "like:expired", "test"));
        redisTemplate.opsForZSet().add(tagKey(), "stale", 100D);
        redisTemplate.opsForZSet().add(authorKey(), "9999", 100D);

        profileService.rebuildProfile(USER_ID);

        assertThat(profileService.profileTags(USER_ID, 5)).containsExactly(Map.entry("redis", 8D));
        assertThat(profileService.profileAuthors(USER_ID, 5)).containsExactly(Map.entry(AUTHOR_ID, 8D));
        assertThat(redisTemplate.getExpire(tagKey(), TimeUnit.DAYS)).isPositive();
        assertThat(redisTemplate.getExpire(authorKey(), TimeUnit.DAYS)).isPositive();
        assertThat(redisTemplate.keys("recommendation:*:" + USER_ID + ":rebuild:*")).isEmpty();
    }

    @Test
    void rebuildCapsRecentFactsBeforeJoiningTagsAndBoundsStoredMembers() {
        insertProfileArticle(2002L, 3002L, 4002L, "java");
        insertProfileArticle(2003L, 3003L, 4003L, "mysql");
        properties.setProfileFactLimit(2);
        properties.setProfileMaxTags(1);
        properties.setProfileMaxAuthors(1);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        insertEvent(new RecommendationEventCommand(USER_ID, ARTICLE_ID, null, RecommendationEventType.COLLECT,
                now.minusMinutes(2), "bounded-profile-old", "test"));
        insertEvent(new RecommendationEventCommand(USER_ID, 2002L, null, RecommendationEventType.LIKE,
                now.minusMinutes(1), "bounded-profile-newer", "test"));
        insertEvent(new RecommendationEventCommand(USER_ID, 2003L, null, RecommendationEventType.VIEW,
                now, "bounded-profile-newest", "test"));

        profileService.rebuildProfile(USER_ID);

        assertThat(profileService.profileTags(USER_ID, 10))
                .containsExactly(Map.entry("java", 4D));
        assertThat(profileService.profileAuthors(USER_ID, 10))
                .containsExactly(Map.entry(3002L, 4D));
        assertThat(redisTemplate.opsForZSet().zCard(tagKey())).isOne();
        assertThat(redisTemplate.opsForZSet().zCard(authorKey())).isOne();
    }

    @Test
    void rebuildCapsTagAssociationsBeforeAggregation() {
        jdbcTemplate.update("INSERT INTO tag (id, name, article_count, create_time) VALUES (4002, 'java', 1, NOW())");
        jdbcTemplate.update("INSERT INTO tag (id, name, article_count, create_time) VALUES (4003, 'mysql', 1, NOW())");
        jdbcTemplate.update("INSERT INTO article_tag (article_id, tag_id) VALUES (?, 4002), (?, 4003)",
                ARTICLE_ID, ARTICLE_ID);
        properties.setProfileTagAssociationLimit(2);
        insertEvent(event(RecommendationEventType.VIEW, ARTICLE_ID, null, "bounded-tag-associations"));

        profileService.rebuildProfile(USER_ID);

        assertThat(profileService.profileTags(USER_ID, 10)).hasSize(2)
                .containsKeys("redis", "java")
                .doesNotContainKey("mysql");
    }

    @Test
    void followAuthorWithoutArticleUpdatesOnlyAuthorProfile() {
        RecommendationEventCommand follow = event(
                RecommendationEventType.FOLLOW_AUTHOR, null, FOLLOWED_AUTHOR_ID, "follow:1001:3002");

        rabbitTemplate.convertAndSend(RecommendationOutboxDispatcher.EVENT_QUEUE, follow);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(eventCount()).isEqualTo(1);
            assertThat(profileService.profileTags(USER_ID, 5)).isEmpty();
            assertThat(profileService.profileAuthors(USER_ID, 5))
                    .containsExactly(Map.entry(FOLLOWED_AUTHOR_ID, 10D));
        });
    }

    private RecommendationEventCommand event(RecommendationEventType type, Long articleId,
                                              Long targetAuthorId, String dedupeKey) {
        return new RecommendationEventCommand(USER_ID, articleId, targetAuthorId, type,
                LocalDateTime.now().withNano(0), dedupeKey, "test");
    }

    private void insertEvent(RecommendationEventCommand event) {
        jdbcTemplate.update("""
                INSERT INTO user_article_event
                    (user_id, article_id, target_author_id, event_type, occurred_at, dedupe_key, source)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, event.userId(), event.articleId(), event.targetAuthorId(), event.eventType().name(),
                event.occurredAt(), event.dedupeKey(), event.source());
    }

    private UserArticleEvent fact(RecommendationEventCommand command) {
        UserArticleEvent fact = new UserArticleEvent();
        fact.setUserId(command.userId());
        fact.setArticleId(command.articleId());
        fact.setTargetAuthorId(command.targetAuthorId());
        fact.setEventType(command.eventType().name());
        fact.setOccurredAt(command.occurredAt());
        fact.setDedupeKey(command.dedupeKey());
        fact.setSource(command.source());
        fact.setCreateTime(LocalDateTime.now().withNano(0));
        return fact;
    }

    private void createProfileCheckpointTable() {
        jdbcTemplate.execute("""
                CREATE TABLE recommendation_profile_checkpoint (
                  user_id BIGINT PRIMARY KEY,
                  requested_event_id BIGINT NOT NULL,
                  rebuilt_event_id BIGINT NOT NULL DEFAULT 0,
                  needs_rebuild TINYINT NOT NULL DEFAULT 1,
                  retry_count INT NOT NULL DEFAULT 0,
                  next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  last_error VARCHAR(500) NULL,
                  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  INDEX idx_profile_checkpoint_due (needs_rebuild, next_attempt_at, user_id)
                ) COMMENT='recommendation profile rebuild checkpoint'
                """);
    }

    private boolean checkpointNeedsRebuild(long userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT needs_rebuild=1 FROM recommendation_profile_checkpoint WHERE user_id=?
                """, Boolean.class, userId));
    }

    private void insertProfileArticle(long articleId, long authorId, long tagId, String tagName) {
        jdbcTemplate.update("""
                INSERT INTO article
                    (id, title, summary, content, author_id, status, is_deleted, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, 1, 0, NOW(), NOW())
                """, articleId, tagName, tagName, tagName, authorId);
        jdbcTemplate.update("INSERT INTO tag (id, name, article_count, create_time) VALUES (?, ?, 1, NOW())",
                tagId, tagName);
        jdbcTemplate.update("INSERT INTO article_tag (article_id, tag_id) VALUES (?, ?)", articleId, tagId);
    }

    private int eventCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_article_event", Integer.class);
    }

    private String eventMetricKey(RecommendationEventCommand event) {
        LocalDate date = event.occurredAt().atZone(clock.getZone())
                .withZoneSameInstant(SHANGHAI).toLocalDate();
        return "recommendation:metrics:" + date + ":event:" + event.eventType().name();
    }

    private static LettuceConnectionFactory failingRedisFactory(int port) {
        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration("127.0.0.1", port);
        SocketOptions socketOptions = SocketOptions.builder().connectTimeout(Duration.ofMillis(100)).build();
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .clientOptions(ClientOptions.builder().socketOptions(socketOptions).build())
                .commandTimeout(Duration.ofMillis(100))
                .shutdownTimeout(Duration.ZERO)
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis, client);
        factory.afterPropertiesSet();
        return factory;
    }

    private static int reserveThenClosePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private String tagKey() {
        return "recommendation:tag:" + USER_ID;
    }

    private String authorKey() {
        return "recommendation:author:" + USER_ID;
    }
}
