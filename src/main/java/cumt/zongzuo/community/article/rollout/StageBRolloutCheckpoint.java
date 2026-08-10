package cumt.zongzuo.community.article.rollout;

import cumt.zongzuo.community.article.config.ArticleRevisionMode;

import java.time.LocalDateTime;

public record StageBRolloutCheckpoint(
        int checkpointId,
        ArticleRevisionMode mode,
        long schemaGeneration,
        long minimumBinaryGeneration,
        String requiredBuildDigest,
        LocalDateTime backfillStartedAt,
        String verifiedBuildDigest,
        String verifiedFingerprint,
        String verifyReportHash,
        LocalDateTime verifiedAt,
        String sentinelBuildDigest,
        String sentinelReportHash,
        LocalDateTime sentinelVerifiedAt,
        long cutoverEpoch,
        String updatedBy,
        LocalDateTime updatedAt,
        long lockVersion) {

    public StageBRolloutCheckpoint {
        if (checkpointId != 1) {
            throw new IllegalArgumentException("rollout checkpoint id must be 1");
        }
        if (mode == null || schemaGeneration < 0 || minimumBinaryGeneration < 0
                || cutoverEpoch < 0 || lockVersion < 0) {
            throw new IllegalArgumentException("rollout checkpoint contains an invalid scalar");
        }
        requiredBuildDigest = ArticleRevisionBuildIdentity.requireDigest(
                requiredBuildDigest, "requiredBuildDigest");
        validateProof(verifiedBuildDigest, verifiedFingerprint, verifyReportHash, verifiedAt,
                "verification");
        validateProof(sentinelBuildDigest, sentinelReportHash, sentinelVerifiedAt, "sentinel");
        if ((mode == ArticleRevisionMode.POINTER_READ || mode == ArticleRevisionMode.CUTOVER)
                && verifiedAt == null) {
            throw new IllegalArgumentException("pointer modes require durable verification proof");
        }
        if (mode == ArticleRevisionMode.CUTOVER
                && (sentinelVerifiedAt == null || cutoverEpoch == 0)) {
            throw new IllegalArgumentException("cutover requires durable sentinel proof and epoch");
        }
        if (cutoverEpoch > 0 && mode != ArticleRevisionMode.POINTER_READ
                && mode != ArticleRevisionMode.CUTOVER) {
            throw new IllegalArgumentException("cutover checkpoint cannot return to a legacy mode");
        }
        if (updatedBy == null || updatedBy.isBlank() || updatedAt == null) {
            throw new IllegalArgumentException("rollout checkpoint audit fields are required");
        }
    }

    private static void validateProof(String buildDigest, String fingerprint,
                                      String reportHash, LocalDateTime verifiedAt,
                                      String proofName) {
        boolean empty = buildDigest == null && fingerprint == null
                && reportHash == null && verifiedAt == null;
        boolean complete = buildDigest != null && fingerprint != null
                && reportHash != null && verifiedAt != null;
        if (!empty && !complete) {
            throw new IllegalArgumentException(proofName + " proof must be complete");
        }
        if (complete) {
            ArticleRevisionBuildIdentity.requireDigest(buildDigest, proofName + "BuildDigest");
            ArticleRevisionBuildIdentity.requireDigest(fingerprint, proofName + "Fingerprint");
            ArticleRevisionBuildIdentity.requireDigest(reportHash, proofName + "ReportHash");
        }
    }

    private static void validateProof(String buildDigest, String reportHash,
                                      LocalDateTime verifiedAt, String proofName) {
        boolean empty = buildDigest == null && reportHash == null && verifiedAt == null;
        boolean complete = buildDigest != null && reportHash != null && verifiedAt != null;
        if (!empty && !complete) {
            throw new IllegalArgumentException(proofName + " proof must be complete");
        }
        if (complete) {
            ArticleRevisionBuildIdentity.requireDigest(buildDigest, proofName + "BuildDigest");
            ArticleRevisionBuildIdentity.requireDigest(reportHash, proofName + "ReportHash");
        }
    }
}
