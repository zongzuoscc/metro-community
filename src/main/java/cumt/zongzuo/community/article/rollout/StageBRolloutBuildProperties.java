package cumt.zongzuo.community.article.rollout;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "metro.article.rollout-build")
public class StageBRolloutBuildProperties {

    private long binaryGeneration = -1;
    private long schemaGeneration = -1;
    private String digest;

    public long getBinaryGeneration() {
        return binaryGeneration;
    }

    public void setBinaryGeneration(long binaryGeneration) {
        this.binaryGeneration = binaryGeneration;
    }

    public long getSchemaGeneration() {
        return schemaGeneration;
    }

    public void setSchemaGeneration(long schemaGeneration) {
        this.schemaGeneration = schemaGeneration;
    }

    public String getDigest() {
        return digest;
    }

    public void setDigest(String digest) {
        this.digest = digest;
    }
}
