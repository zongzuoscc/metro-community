package cumt.zongzuo.community.article.rollout;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.migration.StageBMigrationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StageBRolloutCommandRunnerTest {

    @Test
    void standaloneApplicationRoutesBothMigrationActionsOutsideTheOrdinaryApplication() {
        assertThat(StageBRolloutOperatorApplication.isRequested(new String[]{
                "--metro.migration.stage-b.action=BACKFILL"
        })).isTrue();
        assertThat(StageBRolloutOperatorApplication.isRequested(new String[]{
                "--metro.migration.stage-b.action=VERIFY"
        })).isTrue();
        assertThat(StageBRolloutOperatorApplication.isRequested(new String[]{
                "--metro.migration.stage-b.action=backfill"
        })).isTrue();
        assertThat(StageBRolloutOperatorApplication.isRequested(new String[]{
                "--metro.migration.stage-b.action=NONE"
        })).isFalse();
        assertThat(StageBRolloutOperatorApplication.isRequested(new String[]{
                "--metro.article.rollout-operator.action=none"
        })).isFalse();
    }

    private static final String BUILD_A = "a".repeat(64);
    private static final String BUILD_B = "b".repeat(64);
    private static final String FINGERPRINT = "c".repeat(64);
    private static final String REPORT = "d".repeat(64);

    @TempDir
    Path temporaryDirectory;

    private final ArticleRevisionBuildIdentity currentBuild =
            new ArticleRevisionBuildIdentity(7, 3, BUILD_A);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void advanceAndEmergencyFenceDispatchOnlyThroughTheExactOperator() throws Exception {
        StageBRolloutOperator operator = mock(StageBRolloutOperator.class);
        StageBRolloutCommandProperties advance = properties(StageBRolloutCommandAction.ADVANCE);
        advance.setTarget(ArticleRevisionMode.SHADOW);

        runner(advance, operator).run(null);
        verify(operator).transitionTo(ArticleRevisionMode.SHADOW, currentBuild, "operator-a");

        StageBRolloutCommandProperties emergency =
                properties(StageBRolloutCommandAction.EMERGENCY_FENCE);
        runner(emergency, operator).run(null);
        verify(operator).emergencyFence(currentBuild, "operator-a");
    }

    @Test
    void authorizeBuildUsesATypedTargetIdentity() throws Exception {
        StageBRolloutOperator operator = mock(StageBRolloutOperator.class);
        StageBRolloutCommandProperties properties =
                properties(StageBRolloutCommandAction.AUTHORIZE_BUILD);
        properties.setTargetBinaryGeneration(8);
        properties.setTargetSchemaGeneration(3);
        properties.setTargetBuildDigest(BUILD_B);

        runner(properties, operator).run(null);

        verify(operator).authorizeBuild(
                new ArticleRevisionBuildIdentity(8, 3, BUILD_B),
                currentBuild, "operator-a");
    }

    @Test
    void beginSentinelWritesANewBuildBoundRunFileWithoutOverwritingEvidence()
            throws Exception {
        StageBRolloutOperator operator = mock(StageBRolloutOperator.class);
        StageBPointerSentinelRun run = new StageBPointerSentinelRun(
                17, BUILD_A, FINGERPRINT);
        when(operator.beginPointerSentinel(currentBuild, "operator-a")).thenReturn(run);
        Path runFile = temporaryDirectory.resolve("sentinel-run.json").toAbsolutePath();
        StageBRolloutCommandProperties properties =
                properties(StageBRolloutCommandAction.BEGIN_SENTINEL);
        properties.setSentinelRunPath(runFile.toString());

        runner(properties, operator).run(null);

        assertThat(objectMapper.readValue(runFile.toFile(), StageBPointerSentinelRun.class))
                .isEqualTo(run);
        assertThatThrownBy(() -> runner(properties, operator).run(null))
                .hasMessageContaining("already exists");
    }

    @Test
    void recordSentinelReadsOneStrictControlledTypedReportFile() throws Exception {
        StageBRolloutOperator operator = mock(StageBRolloutOperator.class);
        StageBPointerSentinelReport report = new StageBPointerSentinelReport(
                true, 17, BUILD_A, FINGERPRINT, REPORT);
        Path reportFile = temporaryDirectory.resolve("sentinel-report.json").toAbsolutePath();
        objectMapper.writeValue(reportFile.toFile(), report);
        restrictToOwner(reportFile);
        StageBRolloutCommandProperties properties =
                properties(StageBRolloutCommandAction.RECORD_SENTINEL);
        properties.setSentinelReportPath(reportFile.toString());

        runner(properties, operator).run(null);

        verify(operator).recordPointerSentinelResult(report, currentBuild, "operator-a");

        Path looseFlag = temporaryDirectory.resolve("loose.json").toAbsolutePath();
        Files.writeString(looseFlag, "{\"passed\":true}");
        restrictToOwner(looseFlag);
        properties.setSentinelReportPath(looseFlag.toString());
        assertThatThrownBy(() -> runner(properties, operator).run(null))
                .hasMessageContaining("sentinel");
    }

    @Test
    void actionSpecificInputsFailClosedBeforeCallingTheOperator() {
        StageBRolloutOperator operator = mock(StageBRolloutOperator.class);
        StageBRolloutCommandProperties advance = properties(StageBRolloutCommandAction.ADVANCE);
        assertThatThrownBy(() -> runner(advance, operator).run(null))
                .hasMessageContaining("target");

        StageBRolloutCommandProperties begin =
                properties(StageBRolloutCommandAction.BEGIN_SENTINEL);
        begin.setSentinelRunPath("relative.json");
        assertThatThrownBy(() -> runner(begin, operator).run(null))
                .hasMessageContaining("absolute");

        StageBRolloutCommandProperties record =
                properties(StageBRolloutCommandAction.RECORD_SENTINEL);
        assertThatThrownBy(() -> runner(record, operator).run(null))
                .hasMessageContaining("sentinel report path")
                .hasMessageContaining("required");

        StageBRolloutCommandProperties authorize =
                properties(StageBRolloutCommandAction.AUTHORIZE_BUILD);
        assertThatThrownBy(() -> runner(authorize, operator).run(null))
                .hasMessageContaining("generations")
                .hasMessageContaining("nonnegative");
        verifyNoInteractions(operator);
    }

    private StageBRolloutCommandRunner runner(StageBRolloutCommandProperties properties,
                                               StageBRolloutOperator operator) {
        StageBMigrationProperties migration = new StageBMigrationProperties();
        migration.setOperatorIdentity("operator-a");
        return new StageBRolloutCommandRunner(
                properties, migration, operator, currentBuild, objectMapper);
    }

    private static StageBRolloutCommandProperties properties(StageBRolloutCommandAction action) {
        StageBRolloutCommandProperties properties = new StageBRolloutCommandProperties();
        properties.setAction(action);
        return properties;
    }

    private static void restrictToOwner(Path path) throws Exception {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
        catch (UnsupportedOperationException ignored) {
            // The production reader still applies absolute/no-symlink/size checks cross-platform.
        }
    }
}
