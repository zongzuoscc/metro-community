package cumt.zongzuo.community.article.rollout;

/**
 * Durable token returned after a verification run has invalidated every older proof.
 */
public record StageBVerificationRun(long checkpointVersion, String buildDigest) {

    public StageBVerificationRun {
        if (checkpointVersion < 0) {
            throw new IllegalArgumentException("verification checkpoint version must be nonnegative");
        }
        buildDigest = ArticleRevisionBuildIdentity.requireDigest(
                buildDigest, "verificationBuildDigest");
    }
}
