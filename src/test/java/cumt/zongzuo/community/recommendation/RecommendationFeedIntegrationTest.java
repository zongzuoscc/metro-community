package cumt.zongzuo.community.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.ArticleTagMapper;
import cumt.zongzuo.community.mapper.TagMapper;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import cumt.zongzuo.community.recommendation.dto.RecommendationFeedResponse;
import cumt.zongzuo.community.recommendation.dto.RecommendationExposureDraft;
import cumt.zongzuo.community.recommendation.dto.RecommendationFeatureSnapshot;
import cumt.zongzuo.community.recommendation.dto.RecommendationItem;
import cumt.zongzuo.community.recommendation.dto.RecommendationMode;
import cumt.zongzuo.community.recommendation.dto.RecommendationSession;
import cumt.zongzuo.community.recommendation.dto.RecommendationSessionItem;
import cumt.zongzuo.community.recommendation.dto.RecommendationViewRequest;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventOutbox;
import cumt.zongzuo.community.recommendation.mapper.RecommendationEventOutboxMapper;
import cumt.zongzuo.community.recommendation.mq.RecommendationEventConsumer;
import cumt.zongzuo.community.recommendation.service.RecommendationCandidateService;
import cumt.zongzuo.community.recommendation.service.RecommendationCandidate;
import cumt.zongzuo.community.recommendation.service.RecommendationExposureService;
import cumt.zongzuo.community.recommendation.service.RecommendationEventOutboxService;
import cumt.zongzuo.community.recommendation.service.RecommendationFeedService;
import cumt.zongzuo.community.recommendation.service.RecommendationFeedRateLimiter;
import cumt.zongzuo.community.recommendation.service.RecommendationEligibilityService;
import cumt.zongzuo.community.recommendation.service.RecommendationMetricsService;
import cumt.zongzuo.community.recommendation.service.RecommendationRankingService;
import cumt.zongzuo.community.recommendation.service.RecommendationProfileService;
import cumt.zongzuo.community.recommendation.service.RecommendationSessionStore;
import cumt.zongzuo.community.recommendation.task.RecommendationOutboxDispatcher;
import cumt.zongzuo.community.recommendation.training.RecommendationFeatureVector;
import cumt.zongzuo.community.recommendation.training.RecommendationModel;
import cumt.zongzuo.community.recommendation.training.RecommendationModelStore;
import cumt.zongzuo.community.recommendation.mapper.UserArticleEventMapper;
import cumt.zongzuo.community.service.UserService;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Import(RecommendationFeedIntegrationTest.FixedClockConfiguration.class)
class RecommendationFeedIntegrationTest extends IntegrationTestSupport {

    private static final long USER_ID = 51_001L;
    private static final long OTHER_USER_ID = 51_002L;
    private static final long AUTHOR_ID = 52_001L;
    private static final long OTHER_AUTHOR_ID = 52_002L;
    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime BASE_CREATED_AT = LocalDateTime.of(2026, 8, 9, 18, 0);
    private static final String EVENT_QUEUE = RecommendationOutboxDispatcher.EVENT_QUEUE;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RecommendationProperties properties;
    @Autowired
    private RecommendationSessionStore sessionStore;
    @Autowired
    private RecommendationExposureService exposureService;
    @Autowired
    private RecommendationEventOutboxService outboxService;
    @Autowired
    private RecommendationCandidateService candidateService;
    @Autowired
    private RecommendationRankingService rankingService;
    @Autowired
    private RecommendationEligibilityService eligibilityService;
    @Autowired
    private RecommendationMetricsService metricsService;
    @Autowired
    private RecommendationFeedService feedService;
    @Autowired
    private RecommendationEventOutboxMapper outboxMapper;
    @Autowired
    private RecommendationOutboxDispatcher dispatcher;
    @Autowired
    private RecommendationEventConsumer consumer;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private ArticleTagMapper articleTagMapper;
    @Autowired
    private TagMapper tagMapper;
    @Autowired
    private UserArticleEventMapper eventMapper;
    @Autowired
    private ElasticsearchOperations elasticsearchOperations;
    @Autowired
    private UserService userService;
    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanAndSeedUsers() {
        properties.setEnabled(true);
        properties.setMaxPageSize(20);
        properties.setFeedRequestLimit(20);
        properties.setFeedRateWindowSeconds(60);
        jdbcTemplate.update("DELETE FROM recommendation_event_outbox");
        jdbcTemplate.update("DELETE FROM user_article_event");
        jdbcTemplate.update("DELETE FROM recommendation_exposure");
        jdbcTemplate.update("DELETE FROM article_tag");
        jdbcTemplate.update("DELETE FROM tag");
        jdbcTemplate.update("DELETE FROM article");
        jdbcTemplate.update("DELETE FROM sys_user WHERE id IN (?, ?, ?, ?)",
                USER_ID, OTHER_USER_ID, AUTHOR_ID, OTHER_AUTHOR_ID);
        insertUser(USER_ID, "recommendation-reader", null);
        insertUser(OTHER_USER_ID, "other-reader", null);
        insertUser(AUTHOR_ID, "Alice", "https://img.example/alice.png");
        insertUser(OTHER_AUTHOR_ID, "Bob", "https://img.example/bob.png");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        if (amqpAdmin.getQueueProperties(EVENT_QUEUE) != null) {
            amqpAdmin.purgeQueue(EVENT_QUEUE, true);
        }
    }

    @AfterEach
    void restoreProperties() {
        properties.setEnabled(false);
        properties.setMaxPageSize(20);
        properties.setFeedRequestLimit(20);
        properties.setFeedRateWindowSeconds(60);
    }

    @Test
    void feedRequestRateLimitIsPerUserAtomicAndReturnsHttp429BeforeWritingMoreExposures() {
        properties.setFeedRequestLimit(2);
        properties.setFeedRateWindowSeconds(60);
        insertArticles(4);

        ResponseEntity<String> first = getFeedOverHttp(USER_ID, 1);
        ResponseEntity<String> second = getFeedOverHttp(USER_ID, 1);
        ResponseEntity<String> rejected = getFeedOverHttp(USER_ID, 1);
        ResponseEntity<String> otherUser = getFeedOverHttp(OTHER_USER_ID, 1);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getBody()).contains("\"code\":429");
        assertThat(otherUser.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exposureCount()).isEqualTo(3);
        String key = "recommendation:feed:request:" + USER_ID;
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("3");
        assertThat(redisTemplate.getExpire(key)).isPositive().isLessThanOrEqualTo(60L);
    }

    @Test
    void cursorPagesCannotBypassThePerUserExposureWriteRateLimit() {
        properties.setFeedRequestLimit(2);
        insertArticles(4);

        RecommendationFeedResponse first = feedService.feed(USER_ID, null, 1);
        RecommendationFeedResponse second = feedService.feed(USER_ID, first.nextCursor(), 1);

        assertThatThrownBy(() -> feedService.feed(USER_ID, second.nextCursor(), 1))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(exposureCount()).isEqualTo(2);
    }

    @Test
    void rateLimitRedisFailureFailsOpenAndPreservesTheExistingFallbackFeed() throws Exception {
        properties.setEnabled(false);
        insertArticles(2);
        LettuceConnectionFactory failingFactory = failingRedisFactory(reserveThenClosePort());
        try {
            RecommendationFeedRateLimiter failingLimiter = new RecommendationFeedRateLimiter(
                    new StringRedisTemplate(failingFactory), properties);
            RecommendationFeedService isolated = new RecommendationFeedService(
                    properties, sessionStore, articleMapper, candidateService, rankingService, exposureService,
                    userService, outboxService, metricsService, clock, eligibilityService, null, failingLimiter);

            RecommendationFeedResponse response = isolated.feed(USER_ID, null, 1);

            assertThat(response.mode()).isEqualTo(RecommendationMode.FALLBACK);
            assertThat(response.items()).hasSize(1);
            assertThat(exposureCount()).isOne();
        } finally {
            failingFactory.destroy();
        }
    }

    @Test
    void coldStartUsesStableChronologyStoresNullExposureSessionAndEnrichesAuthorsInBatch() throws Exception {
        insertArticles(12);

        RecommendationFeedResponse first = getFeed(USER_ID, null, 4);

        assertThat(first.mode()).isEqualTo(RecommendationMode.COLD_START);
        assertThat(articleIds(first)).containsExactly(60_012L, 60_011L, 60_010L, 60_009L);
        assertThat(first.items()).allSatisfy(item -> {
            assertThat(item.source()).isEqualTo("CHRONOLOGICAL");
            assertThat(item.reason()).isNull();
            assertThat(item.exposureId()).isNotNull();
            assertThat(item.article().getAuthorName()).isIn("Alice", "Bob");
            assertThat(item.article().getAuthorAvatar()).startsWith("https://img.example/");
        });
        assertThat(exposureCount()).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM recommendation_exposure e
                JOIN article a ON a.id=e.article_id
                WHERE e.article_author_id=a.author_id
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForList("""
                SELECT tag_affinity, author_affinity, similar_score, heat_score, freshness_score,
                       source_follow, source_tag, source_similar, source_explore
                FROM recommendation_exposure ORDER BY article_id DESC
                """))
                .allSatisfy(row -> {
                    assertThat(((Number) row.get("tag_affinity")).doubleValue()).isZero();
                    assertThat(((Number) row.get("author_affinity")).doubleValue()).isZero();
                    assertThat(((Number) row.get("similar_score")).doubleValue()).isZero();
                    assertThat(((Number) row.get("heat_score")).doubleValue()).isPositive();
                    assertThat(((Number) row.get("freshness_score")).doubleValue()).isBetween(0D, 1D);
                    assertThat(((Number) row.get("source_follow")).doubleValue()).isZero();
                    assertThat(((Number) row.get("source_tag")).doubleValue()).isZero();
                    assertThat(((Number) row.get("source_similar")).doubleValue()).isZero();
                    assertThat(((Number) row.get("source_explore")).doubleValue()).isZero();
                });

        String sessionId = decodedSessionId(first.nextCursor());
        JsonNode stored = objectMapper.readTree(redisTemplate.opsForValue().get(
                "recommendation:session:" + sessionId));
        assertThat(stored.path("userId").asText()).isEqualTo(String.valueOf(USER_ID));
        assertThat(stored.path("items")).hasSize(12);
        assertThat(stored.path("items")).allSatisfy(item -> {
            assertThat(item.path("articleId").asText()).isNotBlank();
            assertThat(item.has("article")).isFalse();
            assertThat(item.has("content")).isFalse();
            assertThat(item.has("exposureId")).isFalse();
            assertThat(item.path("snapshot").isNull()).isTrue();
        });
        assertThat(redisTemplate.getExpire("recommendation:session:" + sessionId)).isBetween(500L, 600L);
    }

    @Test
    void personalizedTagDeliveryCountsReturnedItemsAndReplayWhileExposureIdsStayIdempotent() {
        insertArticles(2);
        String sessionId = UUID.randomUUID().toString();
        RecommendationFeatureSnapshot snapshot = new RecommendationFeatureSnapshot(
                0.4, 0.2, 0.0, 0.5, 0.8, 0D, 1D, 0D, 0D);
        sessionStore.save(sessionId, new RecommendationSession(USER_ID, List.of(
                new RecommendationSessionItem(60_002L, "因为你常看 Redis", "TAG", snapshot, 2.5),
                new RecommendationSessionItem(60_001L, "因为你常看 Redis", "TAG", snapshot, 2.0)),
                RecommendationMode.PERSONALIZED));
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((sessionId + ":0").getBytes());

        RecommendationFeedResponse first = getFeed(USER_ID, cursor, 2);
        RecommendationFeedResponse replay = getFeed(USER_ID, cursor, 2);

        String key = "recommendation:metrics:2026-08-09:delivery:TAG";
        assertThat(first.mode()).isEqualTo(RecommendationMode.PERSONALIZED);
        assertThat(first.items()).hasSize(2).allSatisfy(item -> assertThat(item.source()).isEqualTo("TAG"));
        assertThat(exposureIds(replay)).containsExactlyElementsOf(exposureIds(first));
        assertThat(exposureCount()).isEqualTo(2);
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("4");
        assertThat(redisTemplate.getExpire(key)).isPositive()
                .isLessThanOrEqualTo(Duration.ofDays(40).toSeconds());
    }

    @Test
    void coldStartCountsChronologicalButDisabledAndInvalidCursorFallbacksNeverCount() {
        insertArticles(3);

        RecommendationFeedResponse coldStart = getFeed(USER_ID, null, 2);
        String key = "recommendation:metrics:2026-08-09:delivery:CHRONOLOGICAL";
        assertThat(coldStart.mode()).isEqualTo(RecommendationMode.COLD_START);
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("2");

        properties.setEnabled(false);
        assertThat(getFeed(USER_ID, null, 2).mode()).isEqualTo(RecommendationMode.FALLBACK);
        properties.setEnabled(true);
        assertThat(getFeed(USER_ID, "invalid-cursor", 2).mode()).isEqualTo(RecommendationMode.FALLBACK);

        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("2");
        for (String source : List.of("FOLLOW", "TAG", "SIMILAR", "EXPLORE")) {
            assertThat(redisTemplate.opsForValue().get(
                    "recommendation:metrics:2026-08-09:delivery:" + source)).isNull();
        }
    }

    @Test
    void realEligibleModelPathPersistsScoredSnapshotBaselineAndKeepsCursorStableAfterModelChange(@TempDir Path modelDirectory) {
        insertArticles(6);
        seedEligibilityFacts();
        RecommendationModelStore store = new RecommendationModelStore(modelDirectory, objectMapper, 7);
        assertThat(store.publish(model("first", NOW, 0D)).published()).isTrue();
        RecommendationFeedService modelFeed = new RecommendationFeedService(properties, sessionStore, articleMapper,
                candidateService, rankingService, exposureService, userService, outboxService, metricsService, clock,
                eligibilityService, store);

        RecommendationFeedResponse first = modelFeed.feed(USER_ID, null, 3);

        assertThat(first.mode()).isEqualTo(RecommendationMode.PERSONALIZED);
        assertThat(first.items()).hasSize(3).allSatisfy(item -> {
            assertThat(item.source()).isEqualTo("EXPLORE");
            assertThat(item.reason()).isEqualTo("社区近期热议");
        });
        List<java.util.Map<String, Object>> firstExposures = jdbcTemplate.queryForList("""
                SELECT tag_affinity,author_affinity,similar_score,heat_score,freshness_score,
                       source_follow,source_tag,source_similar,source_explore,baseline_score
                FROM recommendation_exposure ORDER BY id
                """);
        assertThat(firstExposures).hasSize(3).allSatisfy(row -> {
            assertThat(((Number) row.get("tag_affinity")).doubleValue()).isZero();
            assertThat(((Number) row.get("author_affinity")).doubleValue()).isZero();
            assertThat(((Number) row.get("similar_score")).doubleValue()).isZero();
            assertThat(((Number) row.get("source_follow")).doubleValue()).isZero();
            assertThat(((Number) row.get("source_tag")).doubleValue()).isZero();
            assertThat(((Number) row.get("source_similar")).doubleValue()).isZero();
            assertThat(((Number) row.get("source_explore")).doubleValue()).isEqualTo(1D);
            assertThat(((Number) row.get("baseline_score")).doubleValue()).isEqualTo(
                    ((Number) row.get("heat_score")).doubleValue() + ((Number) row.get("freshness_score")).doubleValue());
        });
        assertThat(store.publish(model("replacement", NOW, 1D)).published()).isTrue();

        RecommendationFeedResponse second = modelFeed.feed(USER_ID, first.nextCursor(), 3);

        assertThat(second.mode()).isEqualTo(RecommendationMode.PERSONALIZED);
        assertThat(articleIds(second)).doesNotContainAnyElementsOf(articleIds(first));
        assertThat(exposureCount()).isEqualTo(6);
    }

    @Test
    void eligibleAbsentInvalidOrExpiredModelUsesColdStartButOperationalModelFailuresUseFallback(@TempDir Path modelDirectory) throws Exception {
        insertArticles(4);
        seedEligibilityFacts();
        RecommendationModelStore store = new RecommendationModelStore(modelDirectory, objectMapper, 7);
        RecommendationFeedService modelFeed = new RecommendationFeedService(properties, sessionStore, articleMapper,
                candidateService, rankingService, exposureService, userService, outboxService, metricsService, clock,
                eligibilityService, store);

        assertThat(modelFeed.feed(USER_ID, null, 2).mode()).isEqualTo(RecommendationMode.COLD_START);
        Files.writeString(modelDirectory.resolve("active-model.json"), "{invalid");
        assertThat(modelFeed.feed(USER_ID, null, 2).mode()).isEqualTo(RecommendationMode.COLD_START);
        Files.delete(modelDirectory.resolve("active-model.json"));
        assertThat(store.publish(model("expired", NOW.minus(Duration.ofDays(8)), 0D)).published()).isTrue();
        assertThat(modelFeed.feed(USER_ID, null, 2).mode()).isEqualTo(RecommendationMode.COLD_START);
        Files.delete(modelDirectory.resolve("active-model.json"));
        Files.createDirectory(modelDirectory.resolve("active-model.json"));
        assertThat(modelFeed.feed(USER_ID, null, 2).mode()).isEqualTo(RecommendationMode.FALLBACK);

        RecommendationModelStore deniedStore = new RecommendationModelStore(
                modelDirectory.resolve("denied-before-exists"), objectMapper, 7, Files::move,
                path -> { throw new AccessDeniedException(path.toString()); });
        RecommendationFeedService deniedFeed = new RecommendationFeedService(properties, sessionStore, articleMapper,
                candidateService, rankingService, exposureService, userService, outboxService, metricsService, clock,
                eligibilityService, deniedStore);
        assertThat(deniedFeed.feed(USER_ID, null, 2).mode()).isEqualTo(RecommendationMode.FALLBACK);
    }

    @Test
    void nonFiniteInferenceAndARealClosedProfileRedisPortUseFallback(@TempDir Path modelDirectory) throws Exception {
        insertArticles(4);
        seedEligibilityFacts();
        RecommendationModelStore store = new RecommendationModelStore(modelDirectory, objectMapper, 7);
        assertThat(store.publish(extremeHeatModel()).published()).isTrue();
        RecommendationFeedService modelFeed = new RecommendationFeedService(properties, sessionStore, articleMapper,
                candidateService, rankingService, exposureService, userService, outboxService, metricsService, clock,
                eligibilityService, store);

        assertThat(modelFeed.feed(USER_ID, null, 2).mode()).isEqualTo(RecommendationMode.FALLBACK);

        assertThat(store.publish(model("healthy", NOW, 0D)).published()).isTrue();
        int closedPort = reserveThenClosePort();
        LettuceConnectionFactory failingFactory = failingRedisFactory(closedPort);
        try {
            RecommendationCandidateService redisFailingCandidates = new RecommendationCandidateService(articleMapper,
                    articleTagMapper, tagMapper, eventMapper,
                    new RecommendationProfileService(jdbcTemplate, new StringRedisTemplate(failingFactory), properties),
                    elasticsearchOperations, rankingService, clock);
            RecommendationFeedService redisFailingFeed = new RecommendationFeedService(properties, sessionStore,
                    articleMapper, redisFailingCandidates, rankingService, exposureService, userService, outboxService,
                    metricsService, clock, eligibilityService, store);

            assertThat(redisFailingFeed.feed(USER_ID, null, 2).mode()).isEqualTo(RecommendationMode.FALLBACK);
            assertThat(redisTemplate.opsForValue().setIfAbsent("shared-redis-still-alive-after-profile-failure", "yes")).isTrue();
        } finally {
            failingFactory.destroy();
        }
    }

    @Test
    void personalizedSessionPersistsItsOriginalFeatureSnapshotAndCanonicalSource() {
        insertArticles(1);
        String sessionId = UUID.randomUUID().toString();
        RecommendationFeatureSnapshot snapshot = new RecommendationFeatureSnapshot(
                0.11, 0.22, 0.33, 0.44, 0.55, 1D, 1D, 0D, 0D);
        sessionStore.save(sessionId, new RecommendationSession(USER_ID,
                List.of(new RecommendationSessionItem(60_001L, "Because you follow this author",
                        "FOLLOW|TAG", snapshot)), RecommendationMode.PERSONALIZED));
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((sessionId + ":0").getBytes());

        RecommendationFeedResponse response = getFeed(USER_ID, cursor, 1);

        assertThat(response.mode()).isEqualTo(RecommendationMode.PERSONALIZED);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.reason()).isEqualTo("Because you follow this author");
            assertThat(item.source()).isEqualTo("FOLLOW|TAG");
        });
        assertThat(jdbcTemplate.queryForMap("""
                SELECT tag_affinity, author_affinity, similar_score, heat_score, freshness_score,
                       source_follow, source_tag, source_similar, source_explore, source
                FROM recommendation_exposure WHERE session_id = ?
                """, sessionId)).containsEntry("source", "FOLLOW|TAG")
                .containsEntry("tag_affinity", 0.11)
                .containsEntry("author_affinity", 0.22)
                .containsEntry("similar_score", 0.33)
                .containsEntry("heat_score", 0.44)
                .containsEntry("freshness_score", 0.55)
                .containsEntry("source_follow", 1)
                .containsEntry("source_tag", 1)
                .containsEntry("source_similar", 0)
                .containsEntry("source_explore", 0);
    }

    @Test
    void legacySessionItemWithoutSnapshotDeserializesAsNull() {
        String sessionId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set("recommendation:session:" + sessionId, """
                {"userId":%d,"items":[{"articleId":60001,"reason":null,\
                "source":"CHRONOLOGICAL"}],"mode":"COLD_START"}
                """.formatted(USER_ID));

        RecommendationSession loaded = sessionStore.load(sessionId);

        assertThat(loaded.items()).singleElement().satisfies(item -> assertThat(item.snapshot()).isNull());
    }

    @Test
    void cursorPagesPastTenWithoutDuplicatesAndRepeatedCursorReusesExposureIds() {
        insertArticles(13);

        RecommendationFeedResponse first = getFeed(USER_ID, null, 5);
        assertThat(exposureCount()).isEqualTo(5);
        RecommendationFeedResponse second = getFeed(USER_ID, first.nextCursor(), 5);
        assertThat(exposureCount()).isEqualTo(10);
        RecommendationFeedResponse repeatedSecond = getFeed(USER_ID, first.nextCursor(), 5);
        assertThat(exposureCount()).isEqualTo(10);
        RecommendationFeedResponse third = getFeed(USER_ID, second.nextCursor(), 5);

        assertThat(first.mode()).isEqualTo(RecommendationMode.COLD_START);
        assertThat(second.mode()).isEqualTo(RecommendationMode.COLD_START);
        assertThat(third.mode()).isEqualTo(RecommendationMode.COLD_START);
        assertThat(articleIds(second)).containsExactlyElementsOf(articleIds(repeatedSecond));
        assertThat(exposureIds(second)).containsExactlyElementsOf(exposureIds(repeatedSecond));
        assertThat(new HashSet<>(articleIds(first))).doesNotContainAnyElementsOf(articleIds(second));
        assertThat(new HashSet<>(articleIds(first))).doesNotContainAnyElementsOf(articleIds(third));
        assertThat(new HashSet<>(articleIds(second))).doesNotContainAnyElementsOf(articleIds(third));
        assertThat(articleIds(third)).hasSize(3);
        assertThat(third.nextCursor()).isNull();
        assertThat(exposureCount()).isEqualTo(13);

        Set<String> sessionIds = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT session_id FROM recommendation_exposure", String.class));
        assertThat(sessionIds).containsExactly(decodedSessionId(first.nextCursor()));
    }

    @Test
    void sizeIsClampedToOneAndConfiguredMaximum() {
        insertArticles(30);

        assertThat(getFeed(USER_ID, null, 0).items()).hasSize(1);
        assertThat(getFeed(USER_ID, null, 999).items()).hasSize(20);
    }

    @Test
    void malformedCrossUserAndExpiredSessionCursorsReturnNonBlankFallback() {
        insertArticles(6);
        RecommendationFeedResponse first = getFeed(USER_ID, null, 2);

        RecommendationFeedResponse malformed = getFeed(USER_ID, "not-a-valid-cursor", 2);
        RecommendationFeedResponse crossUser = getFeed(OTHER_USER_ID, first.nextCursor(), 2);
        String sessionKey = "recommendation:session:" + decodedSessionId(first.nextCursor());
        redisTemplate.expire(sessionKey, Duration.ofMillis(1));
        await().atMost(Duration.ofSeconds(2)).until(() -> Boolean.FALSE.equals(redisTemplate.hasKey(sessionKey)));
        RecommendationFeedResponse expired = getFeed(USER_ID, first.nextCursor(), 2);

        assertFallbackWithArticles(malformed, 2);
        assertFallbackWithArticles(crossUser, 2);
        assertFallbackWithArticles(expired, 2);
    }

    @Test
    void disabledServingCreatesFreshFirstPageExposureSessionsAndReplaysValidFallbackCursors() {
        insertArticlesWithSharedTimestamps(8);
        properties.setEnabled(false);

        RecommendationFeedResponse first = getFeed(USER_ID, null, 3);
        RecommendationFeedResponse repeatedFirst = getFeed(USER_ID, null, 3);
        RecommendationFeedResponse second = getFeed(USER_ID, first.nextCursor(), 3);
        RecommendationFeedResponse repeatedSecond = getFeed(USER_ID, first.nextCursor(), 3);
        RecommendationFeedResponse otherVisitSecond = getFeed(USER_ID, repeatedFirst.nextCursor(), 3);

        assertThat(first.mode()).isEqualTo(RecommendationMode.FALLBACK);
        assertThat(second.mode()).isEqualTo(RecommendationMode.FALLBACK);
        assertThat(first.items()).hasSize(3);
        assertThat(second.items()).hasSize(3);
        assertThat(articleIds(repeatedFirst)).containsExactlyElementsOf(articleIds(first));
        assertThat(exposureIds(repeatedFirst)).doesNotContainAnyElementsOf(exposureIds(first));
        assertThat(repeatedFirst.nextCursor()).isNotEqualTo(first.nextCursor());
        assertThat(articleIds(first)).doesNotContainAnyElementsOf(articleIds(second));
        assertThat(articleIds(repeatedSecond)).containsExactlyElementsOf(articleIds(second));
        assertThat(exposureIds(repeatedSecond)).containsExactlyElementsOf(exposureIds(second));
        assertThat(articleIds(otherVisitSecond)).containsExactlyElementsOf(articleIds(second));
        assertThat(exposureIds(otherVisitSecond)).doesNotContainAnyElementsOf(exposureIds(second));
        assertThat(exposureCount()).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT session_id) FROM recommendation_exposure", Integer.class)).isEqualTo(4);
    }

    @Test
    void realLettuceConnectionFailureFallsBackWithoutStoppingSharedRedis() throws Exception {
        insertArticles(3);
        int closedPort = reserveThenClosePort();
        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration("127.0.0.1", closedPort);
        SocketOptions socketOptions = SocketOptions.builder().connectTimeout(Duration.ofMillis(100)).build();
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .clientOptions(ClientOptions.builder().socketOptions(socketOptions).build())
                .commandTimeout(Duration.ofMillis(100))
                .shutdownTimeout(Duration.ZERO)
                .build();
        LettuceConnectionFactory failingFactory = new LettuceConnectionFactory(redis, client);
        failingFactory.afterPropertiesSet();
        try {
            RecommendationSessionStore failingStore = new RecommendationSessionStore(
                    new StringRedisTemplate(failingFactory), objectMapper, properties);
            RecommendationFeedRateLimiter failingLimiter = new RecommendationFeedRateLimiter(
                    new StringRedisTemplate(failingFactory), properties);
            RecommendationFeedService serviceWithFailingSessionStore = new RecommendationFeedService(
                    properties, failingStore, articleMapper, candidateService, rankingService, exposureService,
                    userService, outboxService, metricsService, clock, null, null, failingLimiter);

            RecommendationFeedResponse response = serviceWithFailingSessionStore.feed(USER_ID, null, 2);

            assertFallbackWithArticles(response, 2);
            assertThat(exposureCount()).isEqualTo(2);
            assertThat(redisTemplate.opsForValue().setIfAbsent("shared-redis-still-alive", "yes")).isTrue();
        } finally {
            failingFactory.destroy();
        }
    }

    @Test
    void authorCacheRedisFailureFallsBackToOneBatchDatabaseLookup() {
        insertArticles(3);
        List<cumt.zongzuo.community.entity.User> authors = userService.listByIds(
                List.of(AUTHOR_ID, OTHER_AUTHOR_ID));
        UserService redisUnavailableUsers = mock(UserService.class);
        when(redisUnavailableUsers.getUserMapCached(anySet()))
                .thenThrow(new RedisConnectionFailureException("profile cache unavailable"));
        when(redisUnavailableUsers.listByIds(anyCollection())).thenReturn(authors);
        RecommendationFeedService service = new RecommendationFeedService(
                properties, sessionStore, articleMapper, candidateService, rankingService, exposureService,
                redisUnavailableUsers, outboxService, metricsService, clock);

        RecommendationFeedResponse response = service.feed(USER_ID, null, 3);

        assertThat(response.items()).hasSize(3).allSatisfy(item ->
                assertThat(item.article().getAuthorName()).isIn("Alice", "Bob"));
    }

    @Test
    void exposurePageRollsBackAllRowsWhenOneSnapshotIsInvalid() {
        insertArticles(1);
        cumt.zongzuo.community.entity.Article valid = articleMapper.selectPublicById(60_001L);
        cumt.zongzuo.community.entity.Article invalid = new cumt.zongzuo.community.entity.Article();
        invalid.setAuthorId(AUTHOR_ID);
        RecommendationCandidate validCandidate = candidateService.assembleChronologicalFeatures(valid);
        RecommendationCandidate invalidCandidate = candidateService.assembleChronologicalFeatures(invalid);

        assertThatThrownBy(() -> exposureService.recordPage(
                "atomic-session", USER_ID, List.of(
                        chronologicalDraft(validCandidate), chronologicalDraft(invalidCandidate))))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(exposureCount()).isZero();
    }

    @Test
    void directAndRecommendationViewsAreDailyIdempotentAndDispatchToOneFact() throws Exception {
        insertArticles(1);
        RecommendationFeedResponse feed = getFeed(USER_ID, null, 1);
        Long articleId = feed.items().getFirst().article().getId();
        Long exposureId = feed.items().getFirst().exposureId();

        assertThat(postView(USER_ID, articleId, exposureId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postView(USER_ID, articleId, exposureId).getStatusCode()).isEqualTo(HttpStatus.OK);

        List<RecommendationEventOutbox> rows = outboxMapper.selectList(null);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getEventType()).isEqualTo("VIEW");
            assertThat(row.getDedupeKey()).isEqualTo(
                    "view:" + USER_ID + ":article:" + articleId + ":2026-08-09");
            assertThat(row.getSource()).isEqualTo("recommendation:" + exposureId);
        });

        dispatcher.dispatchPending();
        Object command = rabbitTemplate.receiveAndConvert(EVENT_QUEUE, 2_000);
        assertThat(command).isNotNull();
        consumer.consume((cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand) command);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_article_event WHERE event_type='VIEW'", Integer.class)).isEqualTo(1);
    }

    @Test
    void directQualifiedViewUsesArticleDetailSourceAndGetDetailAloneCreatesNoEvent() throws Exception {
        insertArticles(1);
        long articleId = 60_001L;
        HttpHeaders headers = bearerHeaders(USER_ID);

        ResponseEntity<String> detail = restTemplate.exchange(url("/api/article/detail/" + articleId),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outboxMapper.selectList(null)).isEmpty();

        assertThat(postView(USER_ID, articleId, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outboxMapper.selectList(null)).singleElement()
                .extracting(RecommendationEventOutbox::getSource).isEqualTo("article_detail");
    }

    @Test
    void invalidExposureOwnershipOrArticleReturns400WithoutOutbox() throws Exception {
        insertArticles(2);
        RecommendationFeedResponse feed = getFeed(USER_ID, null, 2);
        RecommendationItem first = feed.items().getFirst();
        RecommendationItem second = feed.items().get(1);

        assertThat(postView(OTHER_USER_ID, first.article().getId(), first.exposureId()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postView(USER_ID, second.article().getId(), first.exposureId()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postView(USER_ID, first.article().getId(), Long.MAX_VALUE).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(outboxMapper.selectList(null)).isEmpty();
    }

    @Test
    void unpublishedDeletedAndMissingArticlesReturn404WithoutOutbox() throws Exception {
        insertArticle(70_001L, AUTHOR_ID, BASE_CREATED_AT, 0, 0, 1);
        insertArticle(70_002L, AUTHOR_ID, BASE_CREATED_AT.minusMinutes(1), 1, 1, 1);

        assertThat(postView(USER_ID, 70_001L, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(postView(USER_ID, 70_002L, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(postView(USER_ID, 99_999L, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(outboxMapper.selectList(null)).isEmpty();
    }

    private RecommendationFeedResponse getFeed(long userId, String cursor, int size) {
        String path = "/api/recommendations/feed?size=" + size;
        if (cursor != null) {
            path += "&cursor=" + cursor;
        }
        ResponseEntity<String> response = restTemplate.exchange(
                url(path), HttpMethod.GET, new HttpEntity<>(bearerHeaders(userId)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            JsonNode result = objectMapper.readTree(response.getBody());
            assertThat(result.path("code").asInt()).isEqualTo(200);
            return objectMapper.treeToValue(result.path("data"), RecommendationFeedResponse.class);
        } catch (Exception exception) {
            throw new AssertionError("Unable to parse feed response: " + response.getBody(), exception);
        }
    }

    private ResponseEntity<String> postView(long userId, long articleId, Long exposureId) throws Exception {
        HttpHeaders headers = bearerHeaders(userId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(url("/api/recommendations/views/" + articleId),
                new HttpEntity<>(objectMapper.writeValueAsString(new RecommendationViewRequest(exposureId)), headers),
                String.class);
    }

    private ResponseEntity<String> getFeedOverHttp(long userId, int size) {
        return restTemplate.exchange(url("/api/recommendations/feed?size=" + size), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(userId)), String.class);
    }

    private HttpHeaders bearerHeaders(long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(userId));
        return headers;
    }

    private void assertFallbackWithArticles(RecommendationFeedResponse response, int size) {
        assertThat(response.mode()).isEqualTo(RecommendationMode.FALLBACK);
        assertThat(response.items()).hasSize(size).allSatisfy(item -> {
            assertThat(item.article()).isNotNull();
            assertThat(item.exposureId()).isNotNull();
            assertThat(item.source()).isEqualTo("CHRONOLOGICAL");
        });
    }

    private List<Long> articleIds(RecommendationFeedResponse response) {
        return response.items().stream().map(item -> item.article().getId()).toList();
    }

    private List<Long> exposureIds(RecommendationFeedResponse response) {
        return response.items().stream().map(RecommendationItem::exposureId).toList();
    }

    private int exposureCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_exposure", Integer.class);
    }

    private RecommendationExposureDraft chronologicalDraft(RecommendationCandidate candidate) {
        return new RecommendationExposureDraft(candidate.articleId(), candidate.authorId(), "CHRONOLOGICAL",
                new RecommendationFeatureSnapshot(
                        candidate.tagAffinity(), candidate.authorAffinity(), candidate.similarScore(),
                        candidate.heatScore(), candidate.freshnessScore(), 0D, 0D, 0D, 0D));
    }

    private String decodedSessionId(String cursor) {
        String decoded = new String(Base64.getUrlDecoder().decode(cursor));
        return decoded.substring(0, decoded.indexOf(':'));
    }

    private void insertArticles(int count) {
        IntStream.rangeClosed(1, count).forEach(index -> insertArticle(
                60_000L + index,
                index % 2 == 0 ? AUTHOR_ID : OTHER_AUTHOR_ID,
                BASE_CREATED_AT.plusMinutes(index), 1, 0, index));
    }

    private void insertArticlesWithSharedTimestamps(int count) {
        IntStream.rangeClosed(1, count).forEach(index -> insertArticle(
                60_000L + index, AUTHOR_ID, BASE_CREATED_AT, 1, 0, index));
    }

    private void insertArticle(long id, long authorId, LocalDateTime createTime,
                               int status, int deleted, int metricSeed) {
        jdbcTemplate.update("""
                INSERT INTO article
                    (id, title, summary, content, author_id, view_count, like_count,
                     comment_count, collect_count, status, is_deleted, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, "Article " + id, "Summary " + id, "Content " + id, authorId,
                metricSeed * 10, metricSeed, metricSeed + 1, metricSeed + 2,
                status, deleted, createTime, createTime);
    }

    private void insertUser(long id, String username, String avatar) {
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, email, avatar, role, status, deleted)
                VALUES (?, ?, 'unused', ?, ?, 0, 0, 0)
                """, id, username, username + "@example.com", avatar);
    }

    private void seedEligibilityFacts() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, SHANGHAI).withNano(0);
        for (int index = 0; index < 20; index++) {
            jdbcTemplate.update("INSERT INTO user_article_event (user_id,event_type,occurred_at,dedupe_key,source,create_time) VALUES (?, 'VIEW', ?, ?, 'recommendation', ?)",
                    USER_ID, now.minusDays(30), "eligible-user-" + index, now);
        }
        for (int index = 0; index < 480; index++) {
            jdbcTemplate.update("INSERT INTO user_article_event (user_id,event_type,occurred_at,dedupe_key,source,create_time) VALUES (?, 'VIEW', ?, ?, 'recommendation', ?)",
                    OTHER_USER_ID, now.minusDays(90), "eligible-global-" + index, now);
        }
    }

    private static RecommendationModel model(String version, Instant trainedAt, double tagWeight) {
        List<Double> zeros = List.of(0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D);
        List<Double> weights = List.of(tagWeight, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D);
        List<Double> standardDeviations = List.of(1D, 1D, 1D, 1D, 1D, 1D, 1D, 1D, 1D);
        return new RecommendationModel(version, trainedAt, RecommendationFeatureVector.FEATURE_NAMES,
                zeros, standardDeviations, weights, 0D, .75D, .6D);
    }

    private static RecommendationModel extremeHeatModel() {
        List<Double> zeros = List.of(0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D);
        List<Double> weights = List.of(0D, 0D, 0D, Double.MAX_VALUE, Double.MAX_VALUE, 0D, 0D, 0D, 0D);
        List<Double> standardDeviations = List.of(1D, 1D, 1D, 1D, 1D, 1D, 1D, 1D, 1D);
        return new RecommendationModel("extreme", NOW, RecommendationFeatureVector.FEATURE_NAMES,
                zeros, standardDeviations, weights, 0D, .75D, .6D);
    }

    private static LettuceConnectionFactory failingRedisFactory(int port) {
        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration("127.0.0.1", port);
        SocketOptions socketOptions = SocketOptions.builder().connectTimeout(Duration.ofMillis(100)).build();
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .clientOptions(ClientOptions.builder().socketOptions(socketOptions).build())
                .commandTimeout(Duration.ofMillis(100)).shutdownTimeout(Duration.ZERO).build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis, client);
        factory.afterPropertiesSet();
        return factory;
    }

    private int reserveThenClosePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedRecommendationClock() {
            return Clock.fixed(NOW, SHANGHAI);
        }
    }
}
