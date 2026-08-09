package cumt.zongzuo.community.recommendation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "recommendation")
@EnableConfigurationProperties(RecommendationProperties.class)
public class RecommendationProperties {

    private boolean enabled = false;
    private int sessionTtlMinutes = 10;
    private int profileTtlDays = 35;
    private int profileWindowDays = 30;
    private int defaultPageSize = 10;
    private int maxPageSize = 20;
    private int minimumUserEvents = 20;
    private int minimumGlobalEvents = 500;
    private int modelWindowDays = 90;
    private int labelWindowDays = 7;
    private String modelDirectory = "data/recommendation-models";
}
