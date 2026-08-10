package cumt.zongzuo.community.article.rollout;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StageBCutoverOperationsDocumentationTest {

    private static final Path RUNBOOK = Path.of(
            "docs/database/operations/2026-08-10-stage-b-cutover-runbook.md");

    @Test
    void checkedInConfigurationExposesBuildPromotionAndSafeRetentionControls()
            throws IOException {
        String production = read("src/main/resources/application.yml");
        String example = read("src/main/resources/application-example.yml");
        String environment = read(".env.example");

        for (String configuration : new String[]{production, example}) {
            assertThat(configuration)
                    .contains("METRO_ARTICLE_REVISION_MODE")
                    .contains("METRO_ARTICLE_ROLLOUT_BUILD_DIGEST")
                    .contains("METRO_ARTICLE_ROLLOUT_BINARY_GENERATION")
                    .contains("METRO_ARTICLE_ROLLOUT_SCHEMA_GENERATION")
                    .contains("METRO_STAGE_B_MIGRATION_ACTION")
                    .contains("METRO_STAGE_B_OPERATOR_IDENTITY")
                    .contains("METRO_STAGE_B_VERIFICATION_REPORT_PATH")
                    .contains("METRO_STAGE_B_ROLLOUT_ACTION")
                    .contains("METRO_STAGE_B_ROLLOUT_TARGET")
                    .contains("METRO_STAGE_B_ROLLOUT_SENTINEL_RUN_PATH")
                    .contains("METRO_STAGE_B_ROLLOUT_SENTINEL_REPORT_PATH")
                    .contains("METRO_STAGE_B_ROLLOUT_TARGET_BINARY_GENERATION")
                    .contains("METRO_STAGE_B_ROLLOUT_TARGET_SCHEMA_GENERATION")
                    .contains("METRO_STAGE_B_ROLLOUT_TARGET_BUILD_DIGEST")
                    .contains("METRO_DOMAIN_EVENT_RETENTION_SCHEDULING_ENABLED:false")
                    .contains("METRO_DOMAIN_EVENT_RETENTION_BATCH_SIZE")
                    .contains("METRO_DOMAIN_EVENT_RETENTION_MAX_BATCHES")
                    .contains("METRO_DOMAIN_EVENT_RETENTION_CRON")
                    .contains("METRO_DOMAIN_EVENT_RETENTION_METRICS_ENABLED:true")
                    .contains("METRO_DOMAIN_EVENT_RETENTION_METRICS_DELAY")
                    .contains("METRO_DOMAIN_EVENT_RETENTION_METRICS_INITIAL_DELAY");
        }
        assertThat(environment)
                .contains("METRO_ARTICLE_REVISION_MODE=LEGACY")
                .contains("METRO_ARTICLE_ROLLOUT_BUILD_DIGEST=")
                .contains("METRO_ARTICLE_ROLLOUT_BINARY_GENERATION=")
                .contains("METRO_ARTICLE_ROLLOUT_SCHEMA_GENERATION=")
                .contains("METRO_STAGE_B_MIGRATION_ACTION=NONE")
                .contains("METRO_STAGE_B_OPERATOR_IDENTITY=")
                .contains("METRO_STAGE_B_VERIFICATION_REPORT_PATH=")
                .contains("METRO_STAGE_B_ROLLOUT_ACTION=NONE")
                .contains("METRO_STAGE_B_ROLLOUT_TARGET=")
                .contains("METRO_STAGE_B_ROLLOUT_SENTINEL_RUN_PATH=")
                .contains("METRO_STAGE_B_ROLLOUT_SENTINEL_REPORT_PATH=")
                .contains("METRO_STAGE_B_ROLLOUT_TARGET_BINARY_GENERATION=")
                .contains("METRO_STAGE_B_ROLLOUT_TARGET_SCHEMA_GENERATION=")
                .contains("METRO_STAGE_B_ROLLOUT_TARGET_BUILD_DIGEST=")
                .contains("METRO_DOMAIN_EVENT_RETENTION_SCHEDULING_ENABLED=false")
                .contains("METRO_DOMAIN_EVENT_RETENTION_METRICS_ENABLED=true");
    }

    @Test
    void theSingleAuthoritativeRunbookLocksPromotionDrainAndForwardFixEvidence()
            throws IOException {
        String runbook = Files.readString(RUNBOOK);
        String redirect = read(
                "docs/database/operations/2026-08-10-stage-b-revision-mode-rollout.md");
        String readme = read("README.md");

        assertThat(runbook)
                .contains("authoritative")
                .contains("LEGACY -> SHADOW -> VERIFY_FENCE -> POINTER_READ -> CUTOVER")
                .contains("immutable image digest")
                .contains("admission allowlist")
                .contains("old pod")
                .contains("DB credential")
                .contains("RabbitMQ credential")
                .contains("listener")
                .contains("recovery")
                .contains("job.updated_at")
                .contains("Outbox lag")
                .contains("ES")
                .contains("verification report")
                .contains("METRO_STAGE_B_VERIFICATION_REPORT_PATH")
                .contains("CREATE_NEW")
                .contains("sentinel report")
                .contains("METRO_STAGE_B_ROLLOUT_ACTION")
                .contains("BOOTSTRAP_LEGACY")
                .contains("BEGIN_SENTINEL")
                .contains("RECORD_SENTINEL")
                .contains("AUTHORIZE_BUILD")
                .contains("EMERGENCY_FENCE")
                .contains("emergencyFence")
                .contains("authorizeBuild")
                .contains("NOT RUN")
                .contains("old binary");
        assertThat(redirect)
                .contains("2026-08-10-stage-b-cutover-runbook.md")
                .contains("superseded")
                .doesNotContain("SHADOW may return to LEGACY");
        assertThat(readme)
                .contains("2026-08-10-stage-b-cutover-runbook.md")
                .contains("METRO_ARTICLE_ROLLOUT_BUILD_DIGEST")
                .contains("METRO_DOMAIN_EVENT_RETENTION_SCHEDULING_ENABLED=false");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}
