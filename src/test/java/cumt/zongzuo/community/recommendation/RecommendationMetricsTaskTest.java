package cumt.zongzuo.community.recommendation;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.dto.RecommendationFeedResponse;
import cumt.zongzuo.community.recommendation.dto.RecommendationItem;
import cumt.zongzuo.community.recommendation.dto.RecommendationMode;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventType;
import cumt.zongzuo.community.recommendation.service.RecommendationMetricsService;
import cumt.zongzuo.community.recommendation.task.RecommendationMetricsTask;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(OutputCaptureExtension.class)
@Import(RecommendationMetricsTaskTest.FixedClockConfiguration.class)
class RecommendationMetricsTaskTest extends IntegrationTestSupport {

    private static final ZoneId APPLICATION_ZONE = ZoneId.of("America/Los_Angeles");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-09T16:30:00Z"), APPLICATION_ZONE);
    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 8, 9);

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RecommendationMetricsService metricsService;
    @Autowired
    private RecommendationMetricsTask metricsTask;

    @BeforeEach
    void clearRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void shanghaiYesterdaySnapshotUsesStableOrderAndMissingKeysAreZero(CapturedOutput output) {
        setMetric(REPORT_DATE, "delivery", "FOLLOW", "2");
        setMetric(REPORT_DATE, "delivery", "SIMILAR", "3");
        setMetric(REPORT_DATE, "delivery", "CHRONOLOGICAL", "4");
        setMetric(REPORT_DATE, "event", "VIEW", "5");
        setMetric(REPORT_DATE, "event", "COLLECT", "6");
        setMetric(REPORT_DATE, "event", "FOLLOW_AUTHOR", "7");

        RecommendationMetricsService.DailySnapshot snapshot = metricsService.dailySnapshot(REPORT_DATE);

        assertThat(snapshot.deliveryCounts()).containsExactly(
                Map.entry("FOLLOW", 2L),
                Map.entry("TAG", 0L),
                Map.entry("SIMILAR", 3L),
                Map.entry("EXPLORE", 0L),
                Map.entry("CHRONOLOGICAL", 4L));
        assertThat(snapshot.eventCounts()).containsExactly(
                Map.entry("VIEW", 5L),
                Map.entry("LIKE", 0L),
                Map.entry("COLLECT", 6L),
                Map.entry("COMMENT", 0L),
                Map.entry("FOLLOW_AUTHOR", 7L));

        metricsTask.run();

        assertThat(output.getAll()).contains(
                "recommendation_metrics date=2026-08-09 status=available "
                        + "delivery_follow=2 delivery_tag=0 delivery_similar=3 delivery_explore=0 "
                        + "delivery_chronological=4 event_view=5 event_like=0 event_collect=6 "
                        + "event_comment=0 event_follow_author=7");
    }

    @Test
    void malformedValueMakesTheDailyRunUnavailableWithoutThrowing(CapturedOutput output) {
        setMetric(REPORT_DATE, "delivery", "FOLLOW", "not-a-number");

        assertThatCode(metricsTask::run).doesNotThrowAnyException();

        assertThat(output.getAll())
                .contains("recommendation_metrics date=2026-08-09 status=unavailable")
                .doesNotContain("recommendation_metrics date=2026-08-09 status=available");
    }

    @Test
    void realRedisReadFailureMakesTheDailyRunUnavailableWithoutThrowing(CapturedOutput output) throws Exception {
        LettuceConnectionFactory failingFactory = failingRedisFactory(reserveThenClosePort());
        try {
            RecommendationMetricsService failingMetrics = new RecommendationMetricsService(
                    new StringRedisTemplate(failingFactory), CLOCK);
            RecommendationMetricsTask failingTask = new RecommendationMetricsTask(failingMetrics, CLOCK);

            assertThatCode(failingTask::run).doesNotThrowAnyException();

            assertThat(output.getAll()).contains(
                    "recommendation_metrics date=2026-08-09 status=unavailable");
        } finally {
            failingFactory.destroy();
        }
    }

    @Test
    void eventOccurrenceUsesTheApplicationClockZoneAndLuaRestoresFortyDayTtl() {
        String key = metricKey(LocalDate.of(2026, 8, 10), "event", "VIEW");
        redisTemplate.opsForValue().set(key, "11");
        redisTemplate.persist(key);
        RecommendationEventCommand command = new RecommendationEventCommand(
                1L, 2L, 3L, RecommendationEventType.VIEW,
                LocalDateTime.of(2026, 8, 9, 17, 30), "view:timezone", "test");

        metricsService.recordEvent(command);

        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("12");
        assertThat(redisTemplate.getExpire(key)).isPositive().isLessThanOrEqualTo(Duration.ofDays(40).toSeconds());
    }

    @Test
    void unknownNullAndDelimitedSourcesNeverCreateMetricKeys() {
        RecommendationFeedResponse response = new RecommendationFeedResponse(List.of(
                new RecommendationItem(null, null, null, null),
                new RecommendationItem(null, null, "FOLLOW|TAG", null),
                new RecommendationItem(null, null, "UNKNOWN:source", null)), null,
                RecommendationMode.PERSONALIZED);

        metricsService.recordDeliveries(response);

        for (String source : List.of("FOLLOW", "TAG", "SIMILAR", "EXPLORE", "CHRONOLOGICAL")) {
            assertThat(redisTemplate.hasKey(metricKey(LocalDate.of(2026, 8, 10), "delivery", source))).isFalse();
        }
        assertThat(redisTemplate.hasKey(
                metricKey(LocalDate.of(2026, 8, 10), "delivery", "FOLLOW|TAG"))).isFalse();
        assertThat(redisTemplate.hasKey(
                metricKey(LocalDate.of(2026, 8, 10), "delivery", "UNKNOWN:source"))).isFalse();
    }

    private void setMetric(LocalDate date, String category, String value, String count) {
        redisTemplate.opsForValue().set(metricKey(date, category, value), count);
    }

    private static String metricKey(LocalDate date, String category, String value) {
        return "recommendation:metrics:" + date + ":" + category + ":" + value;
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

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock metricsClock() {
            return CLOCK;
        }
    }
}
