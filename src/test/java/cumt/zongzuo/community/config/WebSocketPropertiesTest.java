package cumt.zongzuo.community.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsTicketTtlToThirtySeconds() {
        contextRunner.run(context -> assertThat(context.getBean(WebSocketProperties.class).ticketTtl())
                .isEqualTo(Duration.ofSeconds(30)));
    }

    @Test
    void bindsConfiguredTicketTtl() {
        contextRunner.withPropertyValues("app.websocket.ticket-ttl=PT45S")
                .run(context -> assertThat(context.getBean(WebSocketProperties.class).ticketTtl())
                        .isEqualTo(Duration.ofSeconds(45)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebSocketProperties.class)
    static class PropertiesConfiguration {
    }
}
