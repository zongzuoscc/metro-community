package cumt.zongzuo.community.article.rollout;

public record StageBPointerSentinelReport(
        boolean passed,
        long checkpointVersion,
        String buildDigest,
        String verifiedFingerprint,
        String reportHash) {

    public StageBPointerSentinelReport {
        if (checkpointVersion < 0) {
            throw new IllegalArgumentException("sentinel checkpoint version must be nonnegative");
        }
        buildDigest = ArticleRevisionBuildIdentity.requireDigest(
                buildDigest, "sentinelBuildDigest");
        verifiedFingerprint = ArticleRevisionBuildIdentity.requireDigest(
                verifiedFingerprint, "sentinelVerifiedFingerprint");
        reportHash = ArticleRevisionBuildIdentity.requireDigest(reportHash, "sentinelReportHash");
    }

    public StageBPointerSentinelReport(
            boolean passed, StageBPointerSentinelRun run, String reportHash) {
        this(passed, run.checkpointVersion(), run.buildDigest(),
                run.verifiedFingerprint(), reportHash);
    }
}
