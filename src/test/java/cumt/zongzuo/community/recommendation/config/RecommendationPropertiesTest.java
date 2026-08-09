package cumt.zongzuo.community.recommendation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RecommendationPropertiesConfiguration.class)
            .withPropertyValues("recommendation.enabled=true", "recommendation.default-page-size=16");

    @Test
    void registersExactlyOneBoundPropertiesBean() {
        contextRunner.run(context -> {
            ApplicationContext applicationContext = context;

            assertThat(applicationContext.getBeansOfType(RecommendationProperties.class))
                    .hasSize(1)
                    .allSatisfy((name, properties) -> {
                        assertThat(properties.isEnabled()).isTrue();
                        assertThat(properties.getDefaultPageSize()).isEqualTo(16);
                        assertThat(properties.getSessionTtlMinutes()).isEqualTo(10);
                    });
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RecommendationProperties.class)
    static class RecommendationPropertiesConfiguration {
    }
}
