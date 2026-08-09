package cumt.zongzuo.community.websocket;

import cumt.zongzuo.community.config.WebSocketProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSocketTicketServiceFailureTest {

    @Test
    void unavailableRedisFailsClosedForIssueAndConsume() {
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(200))
                .shutdownTimeout(Duration.ZERO)
                .build();
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1), clientConfiguration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        WebSocketTicketService service = new WebSocketTicketService(
                redisTemplate, new WebSocketProperties(Duration.ofSeconds(30)));

        try {
            assertThatThrownBy(() -> service.issue(42L))
                    .isInstanceOf(WebSocketTicketStoreException.class);
            assertThatThrownBy(() -> service.consume("a".repeat(43)))
                    .isInstanceOf(WebSocketTicketStoreException.class);
        } finally {
            connectionFactory.destroy();
        }
    }
}
