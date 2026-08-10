package cumt.zongzuo.community.article.migration;

import cumt.zongzuo.community.article.rollout.ArticleRevisionBuildIdentity;
import cumt.zongzuo.community.article.rollout.StageBVerificationRun;

public record StageBVerificationArtifact(
        int formatVersion,
        StageBMigrationReport report,
        ArticleRevisionBuildIdentity buildIdentity,
        StageBVerificationRun verificationRun,
        String operatorIdentity,
        long expectedRecordedCheckpointVersion,
        String reportHash) {

    public StageBVerificationArtifact {
        if (formatVersion != 1 || report == null || buildIdentity == null
                || verificationRun == null || operatorIdentity == null
                || operatorIdentity.isBlank() || expectedRecordedCheckpointVersion < 1
                || reportHash == null || !reportHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("verification artifact is malformed");
        }
        if (!verificationRun.buildDigest().equals(buildIdentity.buildDigest())
                || expectedRecordedCheckpointVersion != verificationRun.checkpointVersion() + 1
                || !reportHash.equals(StageBMigrationReportHasher.hash(report))) {
            throw new IllegalArgumentException("verification artifact binding is invalid");
        }
    }
}
