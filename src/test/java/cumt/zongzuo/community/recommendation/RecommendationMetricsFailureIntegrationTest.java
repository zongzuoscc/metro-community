package cumt.zongzuo.community.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import cumt.zongzuo.community.recommendation.service.RecommendationMetricsService;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@Import(RecommendationMetricsFailureIntegrationTest.FailingMetricsConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class RecommendationMetricsFailureIntegrationTest extends IntegrationTestSupport {

    private static final long USER_ID = 81_001L;
    private static final long AUTHOR_ID = 81_002L;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RecommendationProperties properties;
    @Autowired
    private StringRedisTemplate healthyRedisTemplate;

    @BeforeEach
    void cleanAndSeed() {
        properties.setEnabled(true);
        jdbcTemplate.update("DELETE FROM user_article_event");
        jdbcTemplate.update("DELETE FROM recommendation_exposure");
        jdbcTemplate.update("DELETE FROM article_tag");
        jdbcTemplate.update("DELETE FROM article");
        jdbcTemplate.update("DELETE FROM sys_user WHERE id IN (?, ?)", USER_ID, AUTHOR_ID);
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, email, role, status, deleted)
                VALUES (?, 'metrics-reader', 'unused', 'reader@example.com', 0, 0, 0),
                       (?, 'metrics-author', 'unused', 'author@example.com', 0, 0, 0)
                """, USER_ID, AUTHOR_ID);
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 9, 19, 0);
        jdbcTemplate.update("""
                INSERT INTO article
                    (id, title, summary, content, author_id, status, is_deleted, create_time, update_time)
                VALUES (82001, 'Metric failure', 'summary', 'content', ?, 1, 0, ?, ?),
                       (82002, 'Metric failure 2', 'summary', 'content', ?, 1, 0, ?, ?)
                """, AUTHOR_ID, createdAt, createdAt, AUTHOR_ID,
                createdAt.minusMinutes(1), createdAt.minusMinutes(1));
        healthyRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterAll
    void closeFailingMetricsConnection() {
        properties.setEnabled(false);
        FailingMetricsConfiguration.FAILING_FACTORY.destroy();
    }

    @Test
    void realMetricsRedisFailureDoesNotChangeSuccessfulHttpFeedOrExposureWrites(CapturedOutput output)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(USER_ID));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/recommendations/feed?size=2"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = objectMapper.readTree(response.getBody());
        assertThat(result.path("code").asInt()).isEqualTo(200);
        assertThat(result.path("data").path("mode").asText()).isEqualTo("COLD_START");
        assertThat(result.path("data").path("items")).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recommendation_exposure", Integer.class)).isEqualTo(2);
        assertThat(output.getAll()).contains(
                "Recommendation delivery metrics unavailable; telemetry was dropped");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingMetricsConfiguration {
        private static final LettuceConnectionFactory FAILING_FACTORY = failingRedisFactory();

        @Bean
        @Primary
        RecommendationMetricsService failingRecommendationMetricsService(Clock clock) {
            return new RecommendationMetricsService(new StringRedisTemplate(FAILING_FACTORY), clock);
        }

        private static LettuceConnectionFactory failingRedisFactory() {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to reserve a closed Redis test port", exception);
            }
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
    }
}
