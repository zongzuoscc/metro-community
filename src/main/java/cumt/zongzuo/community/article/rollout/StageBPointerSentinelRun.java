package cumt.zongzuo.community.article.rollout;

/**
 * Durable token binding a pointer sentinel run to one authorized build and verified fingerprint.
 */
public record StageBPointerSentinelRun(
        long checkpointVersion,
        String buildDigest,
        String verifiedFingerprint) {

    public StageBPointerSentinelRun {
        if (checkpointVersion < 0) {
            throw new IllegalArgumentException("sentinel checkpoint version must be nonnegative");
        }
        buildDigest = ArticleRevisionBuildIdentity.requireDigest(
                buildDigest, "sentinelBuildDigest");
        verifiedFingerprint = ArticleRevisionBuildIdentity.requireDigest(
                verifiedFingerprint, "sentinelVerifiedFingerprint");
    }
}
