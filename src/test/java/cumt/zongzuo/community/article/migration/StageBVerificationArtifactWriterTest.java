package cumt.zongzuo.community.article.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.article.rollout.ArticleRevisionBuildIdentity;
import cumt.zongzuo.community.article.rollout.StageBVerificationRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StageBVerificationArtifactWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAnOwnerOnlyNonOverwritableArtifactWithTheExactDurableReportHash()
            throws Exception {
        Path output = temporaryDirectory.resolve("verify-report.json").toAbsolutePath();
        StageBMigrationProperties properties = new StageBMigrationProperties();
        properties.setVerificationReportPath(output.toString());
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        StageBVerificationArtifactWriter writer =
                new StageBVerificationArtifactWriter(properties, objectMapper);
        ArticleRevisionBuildIdentity identity = new ArticleRevisionBuildIdentity(
                7, 3, "a".repeat(64));
        StageBVerificationRun run = new StageBVerificationRun(11, identity.buildDigest());
        StageBMigrationReport report = report();

        StageBVerificationArtifact artifact =
                writer.write(report, run, identity, "operator-a");

        assertThat(artifact.formatVersion()).isEqualTo(1);
        assertThat(artifact.report()).isEqualTo(report);
        assertThat(artifact.buildIdentity()).isEqualTo(identity);
        assertThat(artifact.verificationRun()).isEqualTo(run);
        assertThat(artifact.operatorIdentity()).isEqualTo("operator-a");
        assertThat(artifact.expectedRecordedCheckpointVersion()).isEqualTo(12);
        assertThat(artifact.reportHash()).isEqualTo(StageBMigrationReportHasher.hash(report));
        assertThat(objectMapper.readValue(output.toFile(), StageBVerificationArtifact.class))
                .isEqualTo(artifact);
        try {
            assertThat(Files.getPosixFilePermissions(output))
                    .containsExactlyInAnyOrder(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE);
        }
        catch (UnsupportedOperationException ignored) {
            // File type, absolute path and CREATE_NEW remain enforced on non-POSIX systems.
        }

        assertThatThrownBy(() -> writer.write(report, run, identity, "operator-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void rejectsRelativePathsBeforeProducingAnyArtifact() {
        StageBMigrationProperties properties = new StageBMigrationProperties();
        properties.setVerificationReportPath("relative-report.json");
        StageBVerificationArtifactWriter writer = new StageBVerificationArtifactWriter(
                properties, new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> writer.write(
                report(), new StageBVerificationRun(1, "a".repeat(64)),
                new ArticleRevisionBuildIdentity(1, 1, "a".repeat(64)), "operator-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absolute");
        assertThat(Files.exists(Path.of("relative-report.json"))).isFalse();
    }

    private static StageBMigrationReport report() {
        LocalDateTime started = LocalDateTime.of(2026, 8, 10, 12, 0, 0, 123_000_000);
        LocalDateTime finished = started.plusSeconds(4);
        return new StageBMigrationReport(true, started, finished,
                "b".repeat(64), "b".repeat(64),
                5, 5, 5, 7, 2, 0, 3, 3,
                2, 2, 3, 0, List.of());
    }
}
