package cumt.zongzuo.community.recommendation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "recommendation")
public class RecommendationProperties {

    private boolean enabled = false;
    private int sessionTtlMinutes = 10;
    private int profileTtlDays = 35;
    private int profileWindowDays = 30;
    private int profileFactLimit = 10_000;
    private int profileTagAssociationLimit = 50_000;
    private int profileMaxTags = 100;
    private int profileMaxAuthors = 100;
    private boolean profileRepairEnabled = true;
    private int profileRepairBatchSize = 100;
    private int defaultPageSize = 10;
    private int maxPageSize = 20;
    private int feedRequestLimit = 20;
    private int feedRateWindowSeconds = 60;
    private int minimumUserEvents = 20;
    private int minimumGlobalEvents = 500;
    private int modelWindowDays = 90;
    private int labelWindowDays = 7;
    private int modelMaxAgeDays = 7;
    private int trainingSampleLimit = 50_000;
    private int trainingMaxSamplesPerUser = 500;
    private int trainingFactScanLimit = 200_000;
    private String modelDirectory = "data/recommendation-models";
}
