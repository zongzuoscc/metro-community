package cumt.zongzuo.community.recommendation;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventType;
import cumt.zongzuo.community.recommendation.service.RecommendationProfileService;
import cumt.zongzuo.community.recommendation.task.RecommendationOutboxDispatcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RecommendationEventIntegrationTest extends IntegrationTestSupport {

    private static final long USER_ID = 1001L;
    private static final long ARTICLE_ID = 2001L;
    private static final long AUTHOR_ID = 3001L;
    private static final long FOLLOWED_AUTHOR_ID = 3002L;
    private static final long TAG_ID = 4001L;

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
        });
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

    private int eventCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_article_event", Integer.class);
    }

    private String tagKey() {
        return "recommendation:tag:" + USER_ID;
    }

    private String authorKey() {
        return "recommendation:author:" + USER_ID;
    }
}
