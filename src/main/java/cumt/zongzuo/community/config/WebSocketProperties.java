package cumt.zongzuo.community.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.websocket")
public record WebSocketProperties(Duration ticketTtl) {

    public WebSocketProperties {
        ticketTtl = ticketTtl == null ? Duration.ofSeconds(30) : ticketTtl;
        if (ticketTtl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("WebSocket ticket TTL must be at least one second");
        }
    }
}
