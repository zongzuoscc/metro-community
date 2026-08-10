package cumt.zongzuo.community.article.migration;

import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.article.rollout.ArticleRevisionBuildIdentity;
import cumt.zongzuo.community.article.rollout.StageBRolloutCheckpoint;
import cumt.zongzuo.community.article.rollout.StageBRolloutOperator;
import cumt.zongzuo.community.article.rollout.StageBVerificationRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

public class StageBMigrationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(StageBMigrationRunner.class);

    private final StageBMigrationProperties properties;
    private final ArticleRevisionModeResolver modeResolver;
    private final StageBArticleMigrationService migrationService;
    private final StageBArticleMigrationVerifier verifier;
    private final StageBRolloutOperator rolloutOperator;
    private final ArticleRevisionBuildIdentity buildIdentity;
    private final StageBVerificationArtifactWriter artifactWriter;

    public StageBMigrationRunner(StageBMigrationProperties properties,
                                 ArticleRevisionModeResolver modeResolver,
                                 StageBArticleMigrationService migrationService,
                                 StageBArticleMigrationVerifier verifier,
                                 StageBRolloutOperator rolloutOperator,
                                 ArticleRevisionBuildIdentity buildIdentity,
                                 StageBVerificationArtifactWriter artifactWriter) {
        this.properties = properties;
        this.modeResolver = modeResolver;
        this.migrationService = migrationService;
        this.verifier = verifier;
        this.rolloutOperator = rolloutOperator;
        this.buildIdentity = buildIdentity;
        this.artifactWriter = artifactWriter;
    }

    public void run(ApplicationArguments arguments) {
        runOperatorAction();
    }

    StageBMigrationExecution runOperatorAction() {
        StageBMigrationAction action = properties.getAction();
        ArticleRevisionMode mode = modeResolver.current();
        assertAllowed(action, mode);

        String operatorIdentity = requiredOperatorIdentity(properties.getOperatorIdentity());
        StageBVerificationRun verificationRun = null;
        if (action == StageBMigrationAction.VERIFY) {
            // This must be VERIFY's first durable operation. If any later step crashes, an older
            // PASS is already ineligible and a late completion cannot use this run's token twice.
            verificationRun = rolloutOperator.beginVerification(buildIdentity, operatorIdentity);
        }
        rolloutOperator.markBackfillStarted(buildIdentity, operatorIdentity);
        MigrationRunResult backfill = migrationService.backfillAll(properties.getBatchSize());
        LOGGER.info("Stage B {} backfill finished: batches={}, scanned={}, migrated={}, issues={}",
                action, backfill.batches(), backfill.scanned(), backfill.migrated(), backfill.issues());

        if (action == StageBMigrationAction.BACKFILL) {
            if (backfill.issues() != 0) {
                throw new IllegalStateException("Stage B backfill found unresolved migration issues="
                        + backfill.issues());
            }
            return new StageBMigrationExecution(action, backfill, null);
        }

        StageBMigrationReport report = verifier.verifyAll();
        StageBVerificationArtifact artifact = artifactWriter.write(
                report, verificationRun, buildIdentity, operatorIdentity);
        StageBRolloutCheckpoint checkpoint = rolloutOperator.recordVerificationResult(
                report, verificationRun, buildIdentity, operatorIdentity);
        LOGGER.info("Stage B VERIFY finished: passed={}, mismatches={}, unresolvedArticles={}, "
                        + "mysqlPages={}, elasticsearchPages={}",
                report.passed(), report.mismatchCount(), report.unresolvedIssueArticleCount(),
                report.mysqlPages(), report.elasticsearchPages());
        if (!report.passed()) {
            throw new IllegalStateException("Stage B verification failed: mismatches="
                    + report.mismatchCount() + ", unresolvedArticles="
                    + report.unresolvedIssueArticleCount());
        }
        if (checkpoint.verifiedAt() == null) {
            throw new IllegalStateException(
                    "Stage B verification failed to produce durable verification proof");
        }
        if (!artifact.reportHash().equals(checkpoint.verifyReportHash())
                || artifact.expectedRecordedCheckpointVersion() != checkpoint.lockVersion()
                || !buildIdentity.buildDigest().equals(checkpoint.verifiedBuildDigest())
                || !report.endFingerprint().equals(checkpoint.verifiedFingerprint())) {
            throw new IllegalStateException(
                    "Stage B verification artifact does not match durable verification proof");
        }
        return new StageBMigrationExecution(action, backfill, report);
    }

    private static String requiredOperatorIdentity(String operatorIdentity) {
        if (operatorIdentity == null || operatorIdentity.isBlank()) {
            throw new IllegalStateException(
                    "Stage B migration action requires metro.migration.stage-b.operator-identity");
        }
        return operatorIdentity;
    }

    private static void assertAllowed(StageBMigrationAction action, ArticleRevisionMode mode) {
        if (action == StageBMigrationAction.BACKFILL
                && mode != ArticleRevisionMode.SHADOW
                && mode != ArticleRevisionMode.VERIFY_FENCE) {
            throw new IllegalStateException("Stage B BACKFILL requires SHADOW or VERIFY_FENCE mode");
        }
        if (action == StageBMigrationAction.VERIFY && mode != ArticleRevisionMode.VERIFY_FENCE) {
            throw new IllegalStateException("Stage B VERIFY requires VERIFY_FENCE mode");
        }
        if (action != StageBMigrationAction.BACKFILL && action != StageBMigrationAction.VERIFY) {
            throw new IllegalStateException("Stage B migration runner requires an explicit operator action");
        }
    }
}
