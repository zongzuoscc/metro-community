package cumt.zongzuo.community.article.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "metro.migration.stage-b")
public class StageBMigrationProperties {

    private StageBMigrationAction action = StageBMigrationAction.NONE;
    private int batchSize = 100;
    private int verificationPageSize = 500;
    private int maximumReportedMismatches = 100;
    private String elasticsearchReadAlias = "article-read";
    private Duration elasticsearchPitKeepAlive = Duration.ofMinutes(1);
    private String operatorIdentity;
    private String verificationReportPath;

    public StageBMigrationAction getAction() {
        return action;
    }

    public void setAction(StageBMigrationAction action) {
        this.action = action == null ? StageBMigrationAction.NONE : action;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getVerificationPageSize() {
        return verificationPageSize;
    }

    public void setVerificationPageSize(int verificationPageSize) {
        this.verificationPageSize = verificationPageSize;
    }

    public int getMaximumReportedMismatches() {
        return maximumReportedMismatches;
    }

    public void setMaximumReportedMismatches(int maximumReportedMismatches) {
        this.maximumReportedMismatches = maximumReportedMismatches;
    }

    public String getElasticsearchReadAlias() {
        return elasticsearchReadAlias;
    }

    public void setElasticsearchReadAlias(String elasticsearchReadAlias) {
        this.elasticsearchReadAlias = elasticsearchReadAlias;
    }

    public Duration getElasticsearchPitKeepAlive() {
        return elasticsearchPitKeepAlive;
    }

    public void setElasticsearchPitKeepAlive(Duration elasticsearchPitKeepAlive) {
        this.elasticsearchPitKeepAlive = elasticsearchPitKeepAlive;
    }

    public String getOperatorIdentity() {
        return operatorIdentity;
    }

    public void setOperatorIdentity(String operatorIdentity) {
        this.operatorIdentity = operatorIdentity;
    }

    public String getVerificationReportPath() {
        return verificationReportPath;
    }

    public void setVerificationReportPath(String verificationReportPath) {
        this.verificationReportPath = verificationReportPath;
    }
}
