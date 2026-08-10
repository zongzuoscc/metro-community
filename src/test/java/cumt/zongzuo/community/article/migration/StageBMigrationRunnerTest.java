package cumt.zongzuo.community.article.migration;

import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        JdbcStageBArticleMigrationService service = new JdbcStageBArticleMigrationService(
                null, null, null, () -> ArticleRevisionMode.CUTOVER);

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

    private static StageBMigrationRunner runner(StageBMigrationProperties properties,
                                                ArticleRevisionMode mode,
                                                StageBArticleMigrationService migration,
                                                StageBArticleMigrationVerifier verifier) {
        ArticleRevisionModeResolver resolver = () -> mode;
        return new StageBMigrationRunner(properties, resolver, migration, verifier);
    }

    private static StageBMigrationProperties properties(StageBMigrationAction action) {
        StageBMigrationProperties properties = new StageBMigrationProperties();
        properties.setAction(action);
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
