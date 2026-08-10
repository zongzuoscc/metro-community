package cumt.zongzuo.community.article.rollout;

import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "metro.article.rollout-operator")
public class StageBRolloutCommandProperties {

    private StageBRolloutCommandAction action = StageBRolloutCommandAction.NONE;
    private ArticleRevisionMode target;
    private String sentinelRunPath;
    private String sentinelReportPath;
    private long targetBinaryGeneration = -1;
    private long targetSchemaGeneration = -1;
    private String targetBuildDigest;

    public StageBRolloutCommandAction getAction() {
        return action;
    }

    public void setAction(StageBRolloutCommandAction action) {
        this.action = action == null ? StageBRolloutCommandAction.NONE : action;
    }

    public ArticleRevisionMode getTarget() {
        return target;
    }

    public void setTarget(ArticleRevisionMode target) {
        this.target = target;
    }

    public String getSentinelRunPath() {
        return sentinelRunPath;
    }

    public void setSentinelRunPath(String sentinelRunPath) {
        this.sentinelRunPath = sentinelRunPath;
    }

    public String getSentinelReportPath() {
        return sentinelReportPath;
    }

    public void setSentinelReportPath(String sentinelReportPath) {
        this.sentinelReportPath = sentinelReportPath;
    }

    public long getTargetBinaryGeneration() {
        return targetBinaryGeneration;
    }

    public void setTargetBinaryGeneration(long targetBinaryGeneration) {
        this.targetBinaryGeneration = targetBinaryGeneration;
    }

    public long getTargetSchemaGeneration() {
        return targetSchemaGeneration;
    }

    public void setTargetSchemaGeneration(long targetSchemaGeneration) {
        this.targetSchemaGeneration = targetSchemaGeneration;
    }

    public String getTargetBuildDigest() {
        return targetBuildDigest;
    }

    public void setTargetBuildDigest(String targetBuildDigest) {
        this.targetBuildDigest = targetBuildDigest;
    }
}
