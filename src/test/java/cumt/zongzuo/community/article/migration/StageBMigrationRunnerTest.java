package cumt.zongzuo.community.article.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.article.rollout.ArticleRevisionBuildIdentity;
import cumt.zongzuo.community.article.rollout.StageBRolloutCheckpoint;
import cumt.zongzuo.community.article.rollout.StageBRolloutOperator;
import cumt.zongzuo.community.article.rollout.StageBVerificationRun;
import cumt.zongzuo.community.article.service.ArticleContentCanonicalizer;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class StageBMigrationRunnerTest {

    @Test
    void backfillRefusesModesOutsideShadowAndVerifyFence() {
        RecordingMigrationService migration = new RecordingMigrationService();
        StageBMigrationProperties properties = properties(StageBMigrationAction.BACKFILL);
        StageBMigrationRunner runner = runner(properties, ArticleRevisionMode.LEGACY,
                migration, passingVerifier());

        assertThatThrownBy(runner::runOperatorAction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BACKFILL")
                .hasMessageContaining("SHADOW");
        assertThat(migration.calls()).isZero();
    }

    @Test
    void verifyRefusesEveryModeExceptVerifyFence() {
        RecordingMigrationService migration = new RecordingMigrationService();
        StageBMigrationProperties properties = properties(StageBMigrationAction.VERIFY);
        StageBMigrationRunner runner = runner(properties, ArticleRevisionMode.SHADOW,
                migration, passingVerifier());

        assertThatThrownBy(runner::runOperatorAction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VERIFY")
                .hasMessageContaining("VERIFY_FENCE");
        assertThat(migration.calls()).isZero();
    }

    @Test
    void migrationServiceAlsoRejectsDirectCallsOutsideAllowedModes() {
        ObjectMapper objectMapper = new ObjectMapper();
        JdbcStageBArticleMigrationService service = new JdbcStageBArticleMigrationService(
                null, new ArticleContentCanonicalizer(objectMapper), objectMapper,
                () -> ArticleRevisionMode.CUTOVER);

        assertThatThrownBy(() -> service.backfillAll(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHADOW")
                .hasMessageContaining("VERIFY_FENCE");
    }

    @Test
    void explicitBackfillRunsOneBoundedIdempotentSweepInShadow() {
        RecordingMigrationService migration = new RecordingMigrationService();
        StageBMigrationProperties properties = properties(StageBMigrationAction.BACKFILL);
        properties.setBatchSize(37);
        StageBMigrationRunner runner = runner(properties, ArticleRevisionMode.SHADOW,
                migration, passingVerifier());

        StageBMigrationExecution result = runner.runOperatorAction();

        assertThat(migration.calls()).isOne();
        assertThat(migration.batchSize()).isEqualTo(37);
        assertThat(result.action()).isEqualTo(StageBMigrationAction.BACKFILL);
        assertThat(result.backfill()).isEqualTo(migration.result());
        assertThat(result.report()).isNull();
    }

    @Test
    void verifyPerformsFinalBackfillThenFailsClosedOnAnyMismatch() {
        RecordingMigrationService migration = new RecordingMigrationService();
        StageBMigrationProperties properties = properties(StageBMigrationAction.VERIFY);
        AtomicInteger verificationOrder = new AtomicInteger();
        StageBArticleMigrationVerifier failingVerifier = () -> {
            verificationOrder.set(migration.calls());
            return failingReport();
        };
        StageBMigrationRunner runner = runner(properties, ArticleRevisionMode.VERIFY_FENCE,
                migration, failingVerifier);

        assertThatThrownBy(runner::runOperatorAction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verification failed")
                .hasMessageContaining("mismatches=1");
        assertThat(verificationOrder).hasValue(1);
    }

    @Test
    void verifyDurablyInvalidatesOldProofBeforeTheFinalBackfillCanCrash() {
        StageBMigrationProperties properties = properties(StageBMigrationAction.VERIFY);
        StageBRolloutOperator operator = mock(StageBRolloutOperator.class);
        StageBArticleMigrationService migration = mock(StageBArticleMigrationService.class);
        StageBArticleMigrationVerifier verifier = mock(StageBArticleMigrationVerifier.class);
        ArticleRevisionBuildIdentity identity = new ArticleRevisionBuildIdentity(
                1, 1, "a".repeat(64));
        StageBVerificationRun run = new StageBVerificationRun(7, identity.buildDigest());
        when(operator.beginVerification(identity, "test-operator")).thenReturn(run);
        when(migration.backfillAll(properties.getBatchSize()))
                .thenThrow(new IllegalStateException("simulated crash"));
        StageBMigrationRunner runner = new StageBMigrationRunner(properties,
                () -> ArticleRevisionMode.VERIFY_FENCE, migration, verifier, operator, identity,
                artifactWriter());

        assertThatThrownBy(runner::runOperatorAction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated crash");

        var ordered = inOrder(operator, migration);
        ordered.verify(operator).beginVerification(identity, "test-operator");
        ordered.verify(operator).markBackfillStarted(identity, "test-operator");
        ordered.verify(migration).backfillAll(properties.getBatchSize());
        verifyNoInteractions(verifier);
    }

    @Test
    void verifierPassThatFailsTheLiveFingerprintCheckStillExitsWithFailure() {
        StageBMigrationProperties properties = properties(StageBMigrationAction.VERIFY);
        StageBRolloutOperator operator = mock(StageBRolloutOperator.class);
        ArticleRevisionBuildIdentity identity = new ArticleRevisionBuildIdentity(
                1, 1, "a".repeat(64));
        StageBVerificationRun run = new StageBVerificationRun(7, identity.buildDigest());
        when(operator.beginVerification(identity, "test-operator")).thenReturn(run);
        when(operator.recordVerificationResult(any(StageBMigrationReport.class),
                eq(run), eq(identity), eq("test-operator")))
                .thenReturn(mock(StageBRolloutCheckpoint.class));
        StageBMigrationRunner runner = new StageBMigrationRunner(properties,
                () -> ArticleRevisionMode.VERIFY_FENCE, new RecordingMigrationService(),
                passingVerifier(), operator, identity, artifactWriter());

        assertThatThrownBy(runner::runOperatorAction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable verification proof");
    }

    @Test
    void verifyArtifactFailureLeavesTheBegunRunWithoutAnyDurablePass() {
        StageBMigrationProperties properties = properties(StageBMigrationAction.VERIFY);
        StageBRolloutOperator operator = mock(StageBRolloutOperator.class);
        StageBVerificationArtifactWriter artifactWriter =
                mock(StageBVerificationArtifactWriter.class);
        ArticleRevisionBuildIdentity identity = new ArticleRevisionBuildIdentity(
                1, 1, "a".repeat(64));
        StageBVerificationRun run = new StageBVerificationRun(7, identity.buildDigest());
        StageBMigrationReport report = report(true, 0, List.of());
        when(operator.beginVerification(identity, "test-operator")).thenReturn(run);
        when(artifactWriter.write(report, run, identity, "test-operator"))
                .thenThrow(new IllegalStateException("artifact disk full"));
        StageBMigrationRunner runner = new StageBMigrationRunner(properties,
                () -> ArticleRevisionMode.VERIFY_FENCE, new RecordingMigrationService(),
                () -> report, operator, identity, artifactWriter);

        assertThatThrownBy(runner::runOperatorAction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("artifact disk full");

        verify(operator).beginVerification(identity, "test-operator");
        verify(operator, never()).recordVerificationResult(
                any(), any(), any(), any());
    }

    private static StageBMigrationRunner runner(StageBMigrationProperties properties,
                                                ArticleRevisionMode mode,
                                                StageBArticleMigrationService migration,
                                                StageBArticleMigrationVerifier verifier) {
        ArticleRevisionModeResolver resolver = () -> mode;
        return new StageBMigrationRunner(properties, resolver, migration, verifier,
                mock(StageBRolloutOperator.class),
                new ArticleRevisionBuildIdentity(1, 1, "a".repeat(64)), artifactWriter());
    }

    private static StageBVerificationArtifactWriter artifactWriter() {
        StageBVerificationArtifactWriter writer = mock(StageBVerificationArtifactWriter.class);
        when(writer.write(any(StageBMigrationReport.class), any(StageBVerificationRun.class),
                any(ArticleRevisionBuildIdentity.class), any(String.class)))
                .thenAnswer(invocation -> {
                    StageBMigrationReport report = invocation.getArgument(0);
                    StageBVerificationRun run = invocation.getArgument(1);
                    ArticleRevisionBuildIdentity identity = invocation.getArgument(2);
                    String operator = invocation.getArgument(3);
                    return new StageBVerificationArtifact(1, report, identity, run, operator,
                            run.checkpointVersion() + 1,
                            StageBMigrationReportHasher.hash(report));
                });
        return writer;
    }

    private static StageBMigrationProperties properties(StageBMigrationAction action) {
        StageBMigrationProperties properties = new StageBMigrationProperties();
        properties.setAction(action);
        properties.setOperatorIdentity("test-operator");
        return properties;
    }

    private static StageBArticleMigrationVerifier passingVerifier() {
        return () -> report(true, 0, List.of());
    }

    private static StageBMigrationReport failingReport() {
        return report(false, 1, List.of(
                new StageBMigrationMismatch("TEST_MISMATCH", 1L, "test")));
    }

    private static StageBMigrationReport report(boolean passed, long mismatchCount,
                                                List<StageBMigrationMismatch> mismatches) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 12, 0);
        return new StageBMigrationReport(passed, now, now, "same", "same",
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, mismatchCount, mismatches);
    }

    private static final class RecordingMigrationService implements StageBArticleMigrationService {
        private final MigrationRunResult result = new MigrationRunResult(5, 5, 5, 0, 1);
        private int calls;
        private int batchSize;

        @Override
        public MigrationBatchResult backfillAfter(long afterArticleId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MigrationRunResult backfillAll(int batchSize) {
            this.calls++;
            this.batchSize = batchSize;
            return result;
        }

        int calls() {
            return calls;
        }

        int batchSize() {
            return batchSize;
        }

        MigrationRunResult result() {
            return result;
        }
    }
}
