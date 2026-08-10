package cumt.zongzuo.community.article.rollout;

import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import org.springframework.stereotype.Component;

@Component
public final class JdbcStageBRolloutStartupGate implements StageBRolloutStartupGate {

    private final StageBRolloutCheckpointReader checkpointReader;

    public JdbcStageBRolloutStartupGate(StageBRolloutCheckpointReader checkpointReader) {
        this.checkpointReader = checkpointReader;
    }

    @Override
    public void verify(ArticleRevisionMode configuredMode,
                       ArticleRevisionBuildIdentity buildIdentity) {
        StageBRolloutCheckpoint checkpoint = checkpointReader.require();
        if (checkpoint.mode() != configuredMode) {
            throw new IllegalStateException("configured article revision mode does not match "
                    + "the durable rollout checkpoint mode");
        }
        if (buildIdentity.schemaGeneration() != checkpoint.schemaGeneration()) {
            throw new IllegalStateException(
                    "article revision schema generation does not match checkpoint");
        }
        if (buildIdentity.binaryGeneration() < checkpoint.minimumBinaryGeneration()) {
            throw new IllegalStateException("article revision binary generation is too old");
        }
        if (!buildIdentity.buildDigest().equals(checkpoint.requiredBuildDigest())) {
            throw new IllegalStateException("article revision build digest is not authorized");
        }
        if (configuredMode == ArticleRevisionMode.CUTOVER
                && !checkpoint.requiredBuildDigest().equals(
                checkpoint.sentinelBuildDigest())) {
            throw new IllegalStateException(
                    "CUTOVER sentinel was not produced by the authorized build");
        }
    }
}
