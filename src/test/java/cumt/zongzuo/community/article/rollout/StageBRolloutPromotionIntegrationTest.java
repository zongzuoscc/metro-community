package cumt.zongzuo.community.article.rollout;

import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.article.config.ArticleRevisionProperties;
import cumt.zongzuo.community.article.config.ConfiguredArticleRevisionModeResolver;
import cumt.zongzuo.community.article.migration.MigrationBatchResult;
import cumt.zongzuo.community.article.migration.MigrationRunResult;
import cumt.zongzuo.community.article.migration.JdbcStageBArticleFingerprintService;
import cumt.zongzuo.community.article.migration.StageBArticleMigrationService;
import cumt.zongzuo.community.article.migration.StageBArticleFingerprintService;
import cumt.zongzuo.community.article.migration.StageBMigrationAction;
import cumt.zongzuo.community.article.migration.StageBMigrationMismatch;
import cumt.zongzuo.community.article.migration.StageBMigrationProperties;
import cumt.zongzuo.community.article.migration.StageBMigrationReport;
import cumt.zongzuo.community.article.migration.StageBMigrationRunner;
import cumt.zongzuo.community.article.migration.StageBMigrationReportHasher;
import cumt.zongzuo.community.article.migration.StageBVerificationArtifact;
import cumt.zongzuo.community.article.migration.StageBVerificationArtifactWriter;
import cumt.zongzuo.community.article.service.ArticleMutationGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = false)
@Execution(ExecutionMode.SAME_THREAD)
class StageBRolloutPromotionIntegrationTest {

    private static final String BUILD_A = "a".repeat(64);
    private static final String BUILD_B = "b".repeat(64);
    private static final String FINGERPRINT = "c".repeat(64);
    private static final String REPORT = "d".repeat(64);
    private static final String SENTINEL = "e".repeat(64);

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private JdbcTemplate jdbc;
    private AtomicReference<String> liveFingerprint;
    private StageBRolloutCheckpointReader checkpointReader;
    private StageBRolloutOperator operator;
    private StageBRolloutStartupGate startupGate;
    private ArticleRevisionBuildIdentity buildA;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void createCheckpointStore() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS article_revision_rollout_checkpoint");
        jdbc.execute("""
                CREATE TABLE article_revision_rollout_checkpoint (
                    checkpoint_id TINYINT NOT NULL,
                    mode VARCHAR(24) NOT NULL,
                    schema_generation BIGINT NOT NULL,
                    minimum_binary_generation BIGINT NOT NULL,
                    required_build_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    backfill_started_at DATETIME(6) NULL,
                    verified_build_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    verified_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    verify_report_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    verified_at DATETIME(6) NULL,
                    sentinel_build_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    sentinel_report_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    sentinel_verified_at DATETIME(6) NULL,
                    cutover_epoch BIGINT NOT NULL DEFAULT 0,
                    updated_by VARCHAR(96) NOT NULL,
                    updated_at DATETIME(6) NOT NULL,
                    lock_version BIGINT NOT NULL DEFAULT 0,
                    PRIMARY KEY (checkpoint_id),
                    CONSTRAINT chk_article_revision_rollout_singleton CHECK (checkpoint_id=1)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        liveFingerprint = new AtomicReference<>(FINGERPRINT);
        StageBArticleFingerprintService fingerprintService = liveFingerprint::get;
        checkpointReader = new JdbcStageBRolloutCheckpointReader(jdbc);
        operator = new StageBRolloutOperator(jdbc, checkpointReader, fingerprintService);
        startupGate = new JdbcStageBRolloutStartupGate(checkpointReader);
        buildA = new ArticleRevisionBuildIdentity(7, 3, BUILD_A);
    }

    @Test
    void durableRunnerAndMutationGatesTraverseTheWholeCutoverSequence() {
        operator.bootstrapLegacy(buildA, "operator-a");
        ArticleRevisionModeResolver durableMode = () -> checkpointReader.require().mode();
        ArticleMutationGate mutationGate = new ArticleMutationGate(durableMode);
        RecordingMigrationService migration = new RecordingMigrationService();
        AtomicInteger verifierCalls = new AtomicInteger();

        startupGate.verify(ArticleRevisionMode.LEGACY, buildA);
        assertPublishedEditRejected(mutationGate, "PUBLISHED_ARTICLE_EDIT_REQUIRES_CUTOVER");
        assertThatThrownBy(() -> runner(StageBMigrationAction.BACKFILL, durableMode,
                migration, () -> passingReport(liveFingerprint.get())).run(null))
                .hasMessageContaining("BACKFILL")
                .hasMessageContaining("SHADOW");
        assertThat(migration.calls()).isZero();

        operator.transitionTo(ArticleRevisionMode.SHADOW, buildA, "operator-a");
        startupGate.verify(ArticleRevisionMode.SHADOW, buildA);
        assertThat(mutationGate.requireRevisionWriteMode()).isEqualTo(ArticleRevisionMode.SHADOW);
        assertPublishedEditRejected(mutationGate, "PUBLISHED_ARTICLE_EDIT_REQUIRES_CUTOVER");
        runner(StageBMigrationAction.BACKFILL, durableMode, migration,
                () -> passingReport(liveFingerprint.get())).run(null);
        assertThat(migration.calls()).isOne();
        assertThat(checkpointReader.require().backfillStartedAt()).isNotNull();

        assertThatThrownBy(() -> runner(StageBMigrationAction.VERIFY, durableMode,
                migration, () -> {
                    verifierCalls.incrementAndGet();
                    return passingReport(liveFingerprint.get());
                }).run(null))
                .hasMessageContaining("VERIFY_FENCE");
        assertThat(verifierCalls).hasValue(0);
        assertThat(migration.calls()).isOne();

        operator.transitionTo(ArticleRevisionMode.VERIFY_FENCE, buildA, "operator-a");
        startupGate.verify(ArticleRevisionMode.VERIFY_FENCE, buildA);
        assertWriteFenced(mutationGate);
        runner(StageBMigrationAction.VERIFY, durableMode, migration, () -> {
            verifierCalls.incrementAndGet();
            return passingReport(liveFingerprint.get());
        }).run(null);
        assertThat(verifierCalls).hasValue(1);
        assertThat(migration.calls()).isEqualTo(2);
        assertThat(checkpointReader.require().verifiedAt()).isNotNull();

        operator.transitionTo(ArticleRevisionMode.POINTER_READ, buildA, "operator-a");
        startupGate.verify(ArticleRevisionMode.POINTER_READ, buildA);
        assertWriteFenced(mutationGate);
        StageBPointerSentinelRun sentinelRun =
                operator.beginPointerSentinel(buildA, "operator-a");
        operator.recordPointerSentinelResult(
                new StageBPointerSentinelReport(true, sentinelRun, SENTINEL),
                buildA, "operator-a");

        StageBRolloutCheckpoint cutover = operator.transitionTo(
                ArticleRevisionMode.CUTOVER, buildA, "operator-a");
        startupGate.verify(ArticleRevisionMode.CUTOVER, buildA);
        assertThat(cutover.cutoverEpoch()).isEqualTo(1);
        assertThat(mutationGate.requirePublishedRevisionEditAllowed())
                .isEqualTo(ArticleRevisionMode.CUTOVER);
    }

    @Test
    void startupFailsClosedForMissingMalformedMismatchedOrStaleCheckpoint() {
        assertThatThrownBy(() -> startupGate.verify(ArticleRevisionMode.LEGACY, buildA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checkpoint");

        operator.bootstrapLegacy(buildA, "operator-a");
        startupGate.verify(ArticleRevisionMode.LEGACY, buildA);

        assertThatThrownBy(() -> startupGate.verify(ArticleRevisionMode.SHADOW, buildA))
                .hasMessageContaining("mode");
        assertThatThrownBy(() -> startupGate.verify(ArticleRevisionMode.LEGACY,
                new ArticleRevisionBuildIdentity(6, 3, BUILD_A)))
                .hasMessageContaining("generation");
        assertThatThrownBy(() -> startupGate.verify(ArticleRevisionMode.LEGACY,
                new ArticleRevisionBuildIdentity(7, 4, BUILD_A)))
                .hasMessageContaining("schema");
        assertThatThrownBy(() -> startupGate.verify(ArticleRevisionMode.LEGACY,
                new ArticleRevisionBuildIdentity(7, 3, BUILD_B)))
                .hasMessageContaining("digest");

        jdbc.update("UPDATE article_revision_rollout_checkpoint SET mode='BROKEN' WHERE checkpoint_id=1");
        assertThatThrownBy(() -> startupGate.verify(ArticleRevisionMode.LEGACY, buildA))
                .hasMessageContaining("mode");
    }

    @Test
    void configuredSchemaGenerationMustExactlyMatchForStartupAndOperatorActions() {
        operator.bootstrapLegacy(buildA, "operator-a");
        ArticleRevisionBuildIdentity lowerSchema = new ArticleRevisionBuildIdentity(
                7, 2, BUILD_A);
        ArticleRevisionBuildIdentity higherSchema = new ArticleRevisionBuildIdentity(
                8, 4, BUILD_A);

        assertThatThrownBy(() -> startupGate.verify(ArticleRevisionMode.LEGACY, lowerSchema))
                .hasMessageContaining("schema");
        assertThatThrownBy(() -> startupGate.verify(ArticleRevisionMode.LEGACY, higherSchema))
                .hasMessageContaining("schema");
        assertThatThrownBy(() -> operator.transitionTo(
                ArticleRevisionMode.SHADOW, lowerSchema, "operator-a"))
                .hasMessageContaining("schema");
        assertThatThrownBy(() -> operator.transitionTo(
                ArticleRevisionMode.SHADOW, higherSchema, "operator-a"))
                .hasMessageContaining("schema");

        startupGate.verify(ArticleRevisionMode.LEGACY,
                new ArticleRevisionBuildIdentity(8, 3, BUILD_A));
        operator.transitionTo(ArticleRevisionMode.SHADOW,
                new ArticleRevisionBuildIdentity(8, 3, BUILD_A), "operator-a");
    }

    @Test
    void productionOperatorCliBootstrapsFreshCheckpointBeforeOrdinaryLegacyStartup() {
        assertThatThrownBy(() -> startResolverContext(ArticleRevisionMode.LEGACY, buildA))
                .hasRootCauseMessage("article revision rollout checkpoint is missing");

        StageBRolloutOperatorApplication.run(operatorArguments(
                "BOOTSTRAP_LEGACY", "operator-bootstrap"));

        StageBRolloutCheckpoint checkpoint = checkpointReader.require();
        assertThat(checkpoint.mode()).isEqualTo(ArticleRevisionMode.LEGACY);
        assertThat(checkpoint.requiredBuildDigest()).isEqualTo(BUILD_A);
        assertThat(checkpoint.updatedBy()).isEqualTo("operator-bootstrap");
        try (var context = startResolverContext(ArticleRevisionMode.LEGACY, buildA)) {
            assertThat(context.getBean(ConfiguredArticleRevisionModeResolver.class).current())
                    .isEqualTo(ArticleRevisionMode.LEGACY);
        }
    }

    @Test
    void productionOperatorCliRejectsUnknownActionAndMissingIdentity() {
        assertThatThrownBy(() -> StageBRolloutOperatorApplication.run(
                operatorArguments("NOT_A_REAL_ACTION", "operator-a")))
                .hasStackTraceContaining("rollout-operator.action")
                .hasStackTraceContaining("No enum constant");
        assertThat(checkpointReader.find()).isEmpty();

        assertThatThrownBy(() -> StageBRolloutOperatorApplication.run(
                operatorArguments("BOOTSTRAP_LEGACY", "")))
                .hasMessage(
                        "Stage B rollout operator requires metro.migration.stage-b.operator-identity");
        assertThat(checkpointReader.find()).isEmpty();
    }

    @Test
    void documentedRolloutEnvironmentContractExecutesEveryProductionAction() throws Exception {
        createEmptyFingerprintTables();
        liveFingerprint.set(new JdbcStageBArticleFingerprintService(
                jdbc, new StageBMigrationProperties()).fingerprint());
        runDocumentedRolloutAction("BOOTSTRAP_LEGACY");
        assertThat(checkpointReader.require().mode()).isEqualTo(ArticleRevisionMode.LEGACY);

        runDocumentedRolloutAction("ADVANCE",
                "METRO_STAGE_B_ROLLOUT_TARGET=SHADOW");
        operator.markBackfillStarted(buildA, "operator-doc");
        runDocumentedRolloutAction("ADVANCE",
                "METRO_STAGE_B_ROLLOUT_TARGET=VERIFY_FENCE");
        StageBVerificationRun verificationRun =
                operator.beginVerification(buildA, "operator-doc");
        operator.recordVerificationPass(
                passingReport(liveFingerprint.get()),
                verificationRun, buildA, "operator-doc");
        runDocumentedRolloutAction("ADVANCE",
                "METRO_STAGE_B_ROLLOUT_TARGET=POINTER_READ");

        Path sentinelRunFile = temporaryDirectory.resolve("sentinel-run.json").toAbsolutePath();
        runDocumentedRolloutAction("BEGIN_SENTINEL",
                "METRO_STAGE_B_ROLLOUT_SENTINEL_RUN_PATH=" + sentinelRunFile);
        StageBPointerSentinelRun sentinelRun = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(sentinelRunFile.toFile(), StageBPointerSentinelRun.class);
        Path sentinelReportFile = temporaryDirectory.resolve("sentinel-report.json").toAbsolutePath();
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(
                sentinelReportFile.toFile(),
                new StageBPointerSentinelReport(true, sentinelRun, SENTINEL));
        restrictToOwner(sentinelReportFile);
        runDocumentedRolloutAction("RECORD_SENTINEL",
                "METRO_STAGE_B_ROLLOUT_SENTINEL_REPORT_PATH=" + sentinelReportFile);
        runDocumentedRolloutAction("ADVANCE",
                "METRO_STAGE_B_ROLLOUT_TARGET=CUTOVER");
        runDocumentedRolloutAction("EMERGENCY_FENCE");

        runDocumentedRolloutAction("AUTHORIZE_BUILD",
                "METRO_STAGE_B_ROLLOUT_TARGET_BINARY_GENERATION=8",
                "METRO_STAGE_B_ROLLOUT_TARGET_SCHEMA_GENERATION=3",
                "METRO_STAGE_B_ROLLOUT_TARGET_BUILD_DIGEST=" + BUILD_B);

        StageBRolloutCheckpoint checkpoint = checkpointReader.require();
        assertThat(checkpoint.mode()).isEqualTo(ArticleRevisionMode.POINTER_READ);
        assertThat(checkpoint.requiredBuildDigest()).isEqualTo(BUILD_B);
        assertThat(checkpoint.minimumBinaryGeneration()).isEqualTo(8);
        assertThat(checkpoint.schemaGeneration()).isEqualTo(3);
    }

    @Test
    void durableFingerprintCoversModelEvidenceAttemptsAndExactArticleTags() {
        createEmptyFingerprintTables();
        jdbc.update("""
                INSERT INTO article_moderation_job(
                    id,article_id,revision_id,content_hash,state,attempt_count,
                    created_at,updated_at,lock_version)
                VALUES(1,7,11,?,'HUMAN_PENDING',1,NOW(6),NOW(6),2)
                """, "a".repeat(64));
        JdbcStageBArticleFingerprintService fingerprint =
                new JdbcStageBArticleFingerprintService(jdbc, new StageBMigrationProperties());
        String beforeEvidence = fingerprint.fingerprint();

        jdbc.update("""
                UPDATE article_moderation_job
                SET model_decision='PASS',risk_score=0.1250,
                    policy_hits_json=JSON_OBJECT('categories',JSON_ARRAY('SAFE'))
                WHERE id=1
                """);
        String withEvidence = fingerprint.fingerprint();
        assertThat(withEvidence).isNotEqualTo(beforeEvidence);

        jdbc.update("""
                INSERT INTO article_moderation_attempt(
                    id,job_id,attempt_no,provider,model,prompt_version,input_hash,
                    structured_output_json,latency_ms,token_usage_json,finish_reason,
                    error_code,created_at)
                VALUES(3,1,1,'deepseek','moderation-test','article-moderation-v1',?,
                       JSON_OBJECT('modelOutput',JSON_OBJECT('decision','PASS')),
                       17,JSON_OBJECT('totalTokens',11),'stop',NULL,NOW(6))
                """, "b".repeat(64));
        String withAttempt = fingerprint.fingerprint();
        assertThat(withAttempt).isNotEqualTo(withEvidence);

        jdbc.update("INSERT INTO tag(id,name) VALUES(5,'ExactTag')");
        jdbc.update("INSERT INTO article_tag(id,article_id,tag_id) VALUES(9,7,5)");
        String withTag = fingerprint.fingerprint();
        assertThat(withTag).isNotEqualTo(withAttempt);
        jdbc.update("UPDATE tag SET name='exacttag' WHERE id=5");
        assertThat(fingerprint.fingerprint()).isNotEqualTo(withTag);
    }

    @Test
    void unresolvedMigrationIssueCreatedAfterVerificationBlocksPointerReadPromotion() {
        createEmptyFingerprintTables();
        JdbcStageBArticleFingerprintService fingerprint =
                new JdbcStageBArticleFingerprintService(jdbc, new StageBMigrationProperties());
        StageBRolloutOperator durableOperator = new StageBRolloutOperator(
                jdbc, checkpointReader, fingerprint);

        durableOperator.bootstrapLegacy(buildA, "operator-a");
        durableOperator.transitionTo(ArticleRevisionMode.SHADOW, buildA, "operator-a");
        durableOperator.markBackfillStarted(buildA, "operator-a");
        durableOperator.transitionTo(ArticleRevisionMode.VERIFY_FENCE, buildA, "operator-a");
        String verifiedFingerprint = fingerprint.fingerprint();
        StageBVerificationRun run = durableOperator.beginVerification(buildA, "operator-a");
        durableOperator.recordVerificationPass(
                passingReport(verifiedFingerprint), run, buildA, "operator-a");

        jdbc.update("""
                INSERT INTO article_revision_migration_issue(
                    id,article_id,issue_code,observed_hash,details_json,detected_at,
                    resolved_at,resolution_note)
                VALUES(1,7,'POST_VERIFY_DRIFT',?,JSON_OBJECT('source','test'),
                       NOW(6),NULL,NULL)
                """, "f".repeat(64));

        assertThatThrownBy(() -> durableOperator.transitionTo(
                ArticleRevisionMode.POINTER_READ, buildA, "operator-a"))
                .hasMessageContaining("fingerprint");
        assertThat(checkpointReader.require().mode())
                .isEqualTo(ArticleRevisionMode.VERIFY_FENCE);
    }

    @Test
    void productionBackfillRunsInTheIsolatedOneShotContextWithoutApplicationServices() {
        jdbc.execute("DROP TABLE IF EXISTS article");
        jdbc.execute("""
                CREATE TABLE article (
                    id BIGINT NOT NULL PRIMARY KEY,
                    title VARCHAR(255),summary TEXT,content MEDIUMTEXT,cover VARCHAR(255),
                    author_id BIGINT NOT NULL,status INT,is_deleted INT,
                    latest_revision_id BIGINT NULL,pending_revision_id BIGINT NULL,
                    published_revision_id BIGINT NULL,visibility_state VARCHAR(24) NULL,
                    review_state VARCHAR(24) NULL,lock_version BIGINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB
                """);
        operator.bootstrapLegacy(buildA, "operator-a");
        operator.transitionTo(ArticleRevisionMode.SHADOW, buildA, "operator-a");
        long unchangedVersion = checkpointReader.require().lockVersion();

        assertThatThrownBy(() -> StageBRolloutOperatorApplication.start(
                migrationArguments("backfill", "operator-a", null, null)))
                .hasStackTraceContaining("requires explicit metro.article.revision-mode");
        assertThat(checkpointReader.require().lockVersion()).isEqualTo(unchangedVersion);
        assertThat(checkpointReader.require().backfillStartedAt()).isNull();

        assertThatThrownBy(() -> StageBRolloutOperatorApplication.start(
                migrationArguments("backfill", "operator-a", null,
                        ArticleRevisionMode.VERIFY_FENCE)))
                .hasStackTraceContaining("does not match rollout checkpoint");
        assertThat(checkpointReader.require().lockVersion()).isEqualTo(unchangedVersion);
        assertThat(checkpointReader.require().backfillStartedAt()).isNull();

        try (var context = StageBRolloutOperatorApplication.start(
                migrationArguments("backfill", "operator-a", null,
                        ArticleRevisionMode.SHADOW))) {
            assertThat(context.getBeansOfType(StageBMigrationRunner.class)).hasSize(1);
            assertThat(context.getBeansOfType(ApplicationRunner.class)).hasSize(1);
            assertThat(context.getBeansOfType(ServletWebServerFactory.class)).isEmpty();
            assertThat(context.getBeansOfType(RabbitListenerEndpointRegistry.class)).isEmpty();
            assertThat(context.getBeansOfType(TaskScheduler.class)).isEmpty();
        }

        assertThat(checkpointReader.require().backfillStartedAt()).isNotNull();
    }

    @Test
    void legalPromotionRequiresDurableVerifyFreshFingerprintAndSentinel() {
        operator.bootstrapLegacy(buildA, "operator-a");
        assertThatThrownBy(() -> operator.transitionTo(
                ArticleRevisionMode.POINTER_READ, buildA, "operator-a"))
                .hasMessageContaining("illegal");
        assertThatThrownBy(() -> operator.transitionTo(
                ArticleRevisionMode.CUTOVER, buildA, "operator-a"))
                .hasMessageContaining("illegal");
        operator.transitionTo(ArticleRevisionMode.SHADOW, buildA, "operator-a");
        operator.markBackfillStarted(buildA, "operator-a");
        assertThatThrownBy(() -> operator.transitionTo(
                ArticleRevisionMode.LEGACY, buildA, "operator-a"))
                .hasMessageContaining("backfill");
        operator.transitionTo(ArticleRevisionMode.VERIFY_FENCE, buildA, "operator-a");

        assertThatThrownBy(() -> operator.transitionTo(
                ArticleRevisionMode.POINTER_READ, buildA, "operator-a"))
                .hasMessageContaining("verification");
        StageBVerificationRun verificationRun =
                operator.beginVerification(buildA, "operator-a");
        operator.recordVerificationPass(
                passingReport(FINGERPRINT), verificationRun, buildA, "operator-a");

        liveFingerprint.set("f".repeat(64));
        assertThatThrownBy(() -> operator.transitionTo(
                ArticleRevisionMode.POINTER_READ, buildA, "operator-a"))
                .hasMessageContaining("fingerprint");
        liveFingerprint.set(FINGERPRINT);
        operator.transitionTo(ArticleRevisionMode.POINTER_READ, buildA, "operator-a");
        try (var context = startResolverContext(ArticleRevisionMode.POINTER_READ, buildA)) {
            assertThat(context.getBean(ConfiguredArticleRevisionModeResolver.class).current())
                    .isEqualTo(ArticleRevisionMode.POINTER_READ);
        }

        assertThatThrownBy(() -> operator.transitionTo(
                ArticleRevisionMode.CUTOVER, buildA, "operator-a"))
                .hasMessageContaining("sentinel");
        StageBPointerSentinelRun sentinelRun =
                operator.beginPointerSentinel(buildA, "operator-a");
        operator.recordPointerSentinelResult(
                new StageBPointerSentinelReport(true, sentinelRun, SENTINEL),
                buildA, "operator-a");
        StageBRolloutCheckpoint cutover = operator.transitionTo(
                ArticleRevisionMode.CUTOVER, buildA, "operator-a");
        assertThat(cutover.mode()).isEqualTo(ArticleRevisionMode.CUTOVER);
        assertThat(cutover.cutoverEpoch()).isEqualTo(1);
        try (var context = startResolverContext(ArticleRevisionMode.CUTOVER, buildA)) {
            assertThat(context.getBean(ConfiguredArticleRevisionModeResolver.class).current())
                    .isEqualTo(ArticleRevisionMode.CUTOVER);
        }
        jdbc.update("""
                UPDATE article_revision_rollout_checkpoint SET sentinel_build_digest=?
                WHERE checkpoint_id=1
                """, BUILD_B);
        assertThatThrownBy(() -> startResolverContext(ArticleRevisionMode.CUTOVER, buildA))
                .hasRootCauseMessage(
                        "CUTOVER sentinel was not produced by the authorized build");
        jdbc.update("""
                UPDATE article_revision_rollout_checkpoint SET sentinel_build_digest=?
                WHERE checkpoint_id=1
                """, BUILD_A);

        assertThatThrownBy(() -> operator.transitionTo(
                ArticleRevisionMode.SHADOW, buildA, "operator-a"))
                .hasMessageContaining("irreversible");
        operator.emergencyFence(buildA, "operator-a");
        assertThatThrownBy(() -> operator.transitionTo(
                ArticleRevisionMode.VERIFY_FENCE, buildA, "operator-a"))
                .hasMessageContaining("irreversible");
    }

    @Test
    void failedVerificationAfterPassClearsTheOldProofBeforeFailingClosed() {
        operator.bootstrapLegacy(buildA, "operator-a");
        operator.transitionTo(ArticleRevisionMode.SHADOW, buildA, "operator-a");
        operator.markBackfillStarted(buildA, "operator-a");
        operator.transitionTo(ArticleRevisionMode.VERIFY_FENCE, buildA, "operator-a");
        StageBVerificationRun passingRun =
                operator.beginVerification(buildA, "operator-a");
        operator.recordVerificationPass(
                passingReport(FINGERPRINT), passingRun, buildA, "operator-a");

        StageBVerificationRun failingRun =
                operator.beginVerification(buildA, "operator-a");
        StageBRolloutCheckpoint failed = operator.recordVerificationResult(
                failingReport(FINGERPRINT), failingRun, buildA, "operator-a");

        assertThat(failed.verifiedAt()).isNull();
        assertThat(failed.verifiedBuildDigest()).isNull();
        assertThat(failed.verifiedFingerprint()).isNull();
        assertThat(failed.verifyReportHash()).isNull();
        assertThatThrownBy(() -> operator.transitionTo(
                ArticleRevisionMode.POINTER_READ, buildA, "operator-a"))
                .hasMessageContaining("verification");
    }

    @Test
    void beginningARepeatVerificationInvalidatesAnOldPassBeforeTheVerifierCanCrash() {
        operator.bootstrapLegacy(buildA, "operator-a");
        operator.transitionTo(ArticleRevisionMode.SHADOW, buildA, "operator-a");
        operator.markBackfillStarted(buildA, "operator-a");
        operator.transitionTo(ArticleRevisionMode.VERIFY_FENCE, buildA, "operator-a");
        StageBVerificationRun passingRun =
                operator.beginVerification(buildA, "operator-a");
        operator.recordVerificationPass(
                passingReport(FINGERPRINT), passingRun, buildA, "operator-a");

        StageBVerificationRun begun = operator.beginVerification(buildA, "operator-a");
        StageBRolloutCheckpoint checkpoint = new JdbcStageBRolloutCheckpointReader(jdbc).require();

        assertThat(begun.checkpointVersion()).isEqualTo(checkpoint.lockVersion());
        assertThat(checkpoint.verifiedAt()).isNull();
        assertThat(checkpoint.verifiedBuildDigest()).isNull();
        assertThat(checkpoint.verifiedFingerprint()).isNull();
        assertThat(checkpoint.verifyReportHash()).isNull();
        assertThatThrownBy(() -> operator.transitionTo(
                ArticleRevisionMode.POINTER_READ, buildA, "operator-a"))
                .hasMessageContaining("verification");
    }

    @Test
    void anOlderVerificationCompletionCannotOverwriteANewerFailedRun() {
        operator.bootstrapLegacy(buildA, "operator-a");
        operator.transitionTo(ArticleRevisionMode.SHADOW, buildA, "operator-a");
        operator.markBackfillStarted(buildA, "operator-a");
        operator.transitionTo(ArticleRevisionMode.VERIFY_FENCE, buildA, "operator-a");

        StageBVerificationRun olderRun = operator.beginVerification(buildA, "operator-a");
        StageBVerificationRun newerRun = operator.beginVerification(buildA, "operator-b");
        operator.recordVerificationResult(
                failingReport(FINGERPRINT), newerRun, buildA, "operator-b");

        assertThatThrownBy(() -> operator.recordVerificationResult(
                passingReport(FINGERPRINT), olderRun, buildA, "operator-a"))
                .as("completion from checkpoint version %s must be stale",
                        olderRun.checkpointVersion())
                .hasMessageContaining("CAS");
        assertThat(new JdbcStageBRolloutCheckpointReader(jdbc).require().verifiedAt()).isNull();
    }

    @Test
    void configuredResolverContextFailsStartupAndNeverMutatesTheCheckpoint() {
        assertThatThrownBy(() -> startResolverContext(ArticleRevisionMode.LEGACY, buildA))
                .hasRootCauseMessage("article revision rollout checkpoint is missing");

        operator.bootstrapLegacy(buildA, "operator-a");
        long version = jdbc.queryForObject("""
                SELECT lock_version FROM article_revision_rollout_checkpoint WHERE checkpoint_id=1
                """, Long.class);
        try (var context = startResolverContext(ArticleRevisionMode.LEGACY, buildA)) {
            assertThat(context.getBean(ConfiguredArticleRevisionModeResolver.class).current())
                    .isEqualTo(ArticleRevisionMode.LEGACY);
        }
        assertThat(jdbc.queryForObject("""
                SELECT lock_version FROM article_revision_rollout_checkpoint WHERE checkpoint_id=1
                """, Long.class)).isEqualTo(version);

        assertThatThrownBy(() -> startResolverContext(ArticleRevisionMode.SHADOW, buildA))
                .hasRootCauseMessage(
                        "configured article revision mode does not match the durable rollout checkpoint mode");
        assertThatThrownBy(() -> startResolverContext(ArticleRevisionMode.LEGACY,
                new ArticleRevisionBuildIdentity(7, 3, BUILD_B)))
                .hasRootCauseMessage("article revision build digest is not authorized");
        assertThat(jdbc.queryForObject("""
                SELECT lock_version FROM article_revision_rollout_checkpoint WHERE checkpoint_id=1
                """, Long.class)).isEqualTo(version);
    }

    @Test
    void pointerReadCanAuthorizeOneForwardFixBuildButMustReattestBeforeCutover() {
        promoteToPointerRead();
        ArticleRevisionBuildIdentity buildB = new ArticleRevisionBuildIdentity(8, 3, BUILD_B);

        operator.authorizeBuild(buildB, buildA, "operator-a");
        startupGate.verify(ArticleRevisionMode.POINTER_READ, buildB);
        assertThatThrownBy(() -> startupGate.verify(ArticleRevisionMode.POINTER_READ,
                new ArticleRevisionBuildIdentity(8, 3, BUILD_A)))
                .hasMessageContaining("digest");
        assertThatThrownBy(() -> operator.transitionTo(
                ArticleRevisionMode.CUTOVER, buildB, "operator-b"))
                .hasMessageContaining("sentinel");

        StageBPointerSentinelRun sentinelRun =
                operator.beginPointerSentinel(buildB, "operator-b");
        operator.recordPointerSentinelResult(
                new StageBPointerSentinelReport(true, sentinelRun, SENTINEL),
                buildB, "operator-b");
        StageBRolloutCheckpoint checkpoint = operator.transitionTo(
                ArticleRevisionMode.CUTOVER, buildB, "operator-b");
        assertThat(checkpoint.verifiedBuildDigest()).isEqualTo(BUILD_A);
        assertThat(checkpoint.sentinelBuildDigest()).isEqualTo(BUILD_B);
    }

    @Test
    void forwardFixCannotClaimAHigherSchemaGenerationWithoutANewMigrationAndVerifyCycle() {
        promoteToPointerRead();
        ArticleRevisionBuildIdentity unsupportedSchema =
                new ArticleRevisionBuildIdentity(8, 4, BUILD_B);

        assertThatThrownBy(() -> operator.authorizeBuild(
                unsupportedSchema, buildA, "operator-a"))
                .hasMessageContaining("schema generation")
                .hasMessageContaining("VERIFY");

        StageBRolloutCheckpoint unchanged = checkpointReader.require();
        assertThat(unchanged.schemaGeneration()).isEqualTo(3);
        assertThat(unchanged.requiredBuildDigest()).isEqualTo(BUILD_A);
    }

    @Test
    void anOldBuildSentinelCannotBeRelabeledAfterAForwardFixIsAuthorized() {
        promoteToPointerRead();
        StageBPointerSentinelRun buildARun =
                operator.beginPointerSentinel(buildA, "operator-a");
        StageBPointerSentinelReport staleBuildAReport = new StageBPointerSentinelReport(
                true, buildARun, SENTINEL);
        ArticleRevisionBuildIdentity buildB = new ArticleRevisionBuildIdentity(8, 3, BUILD_B);

        operator.authorizeBuild(buildB, buildA, "operator-a");

        assertThatThrownBy(() -> operator.recordPointerSentinelResult(
                staleBuildAReport, buildB, "operator-b"))
                .hasMessageContaining("sentinel");
        assertThat(new JdbcStageBRolloutCheckpointReader(jdbc).require().sentinelVerifiedAt())
                .isNull();

        StageBPointerSentinelRun buildBRun =
                operator.beginPointerSentinel(buildB, "operator-b");
        StageBRolloutCheckpoint attested = operator.recordPointerSentinelResult(
                new StageBPointerSentinelReport(true, buildBRun, REPORT),
                buildB, "operator-b");
        assertThat(attested.sentinelBuildDigest()).isEqualTo(BUILD_B);
    }

    @Test
    void aFailedSentinelRerunDurablyInvalidatesAnOlderPass() {
        promoteToPointerRead();
        StageBPointerSentinelRun passingRun =
                operator.beginPointerSentinel(buildA, "operator-a");
        operator.recordPointerSentinelResult(
                new StageBPointerSentinelReport(true, passingRun, SENTINEL),
                buildA, "operator-a");

        StageBPointerSentinelRun failingRun =
                operator.beginPointerSentinel(buildA, "operator-a");
        StageBRolloutCheckpoint checkpoint = operator.recordPointerSentinelResult(
                new StageBPointerSentinelReport(false, failingRun, REPORT),
                buildA, "operator-a");

        assertThat(checkpoint.sentinelVerifiedAt()).isNull();
        assertThat(checkpoint.sentinelBuildDigest()).isNull();
        assertThat(checkpoint.sentinelReportHash()).isNull();
        assertThatThrownBy(() -> operator.transitionTo(
                ArticleRevisionMode.CUTOVER, buildA, "operator-a"))
                .hasMessageContaining("sentinel");
    }

    @Test
    void anOlderSentinelCompletionCannotOverwriteANewerFailedRun() {
        promoteToPointerRead();
        StageBPointerSentinelRun olderRun =
                operator.beginPointerSentinel(buildA, "operator-a");
        StageBPointerSentinelRun newerRun =
                operator.beginPointerSentinel(buildA, "operator-b");
        operator.recordPointerSentinelResult(
                new StageBPointerSentinelReport(false, newerRun, REPORT),
                buildA, "operator-b");

        assertThatThrownBy(() -> operator.recordPointerSentinelResult(
                new StageBPointerSentinelReport(true, olderRun, SENTINEL),
                buildA, "operator-a"))
                .hasMessageContaining("CAS");
        assertThat(new JdbcStageBRolloutCheckpointReader(jdbc).require().sentinelVerifiedAt())
                .isNull();
    }

    @Test
    void concurrentOperatorTransitionUsesExactCheckpointCasAndOnlyOneWins() throws Exception {
        operator.bootstrapLegacy(buildA, "operator-a");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> transitionAfterBarrier(ready, start));
            var second = executor.submit(() -> transitionAfterBarrier(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(new JdbcStageBRolloutCheckpointReader(jdbc).require().mode())
                .isEqualTo(ArticleRevisionMode.SHADOW);
    }

    private boolean transitionAfterBarrier(CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            operator.transitionTo(ArticleRevisionMode.SHADOW, buildA, "operator-race");
            return true;
        }
        catch (IllegalStateException conflict) {
            return false;
        }
    }

    private void promoteToPointerRead() {
        operator.bootstrapLegacy(buildA, "operator-a");
        operator.transitionTo(ArticleRevisionMode.SHADOW, buildA, "operator-a");
        operator.markBackfillStarted(buildA, "operator-a");
        operator.transitionTo(ArticleRevisionMode.VERIFY_FENCE, buildA, "operator-a");
        StageBVerificationRun run = operator.beginVerification(buildA, "operator-a");
        operator.recordVerificationPass(
                passingReport(FINGERPRINT), run, buildA, "operator-a");
        operator.transitionTo(ArticleRevisionMode.POINTER_READ, buildA, "operator-a");
    }

    private static StageBMigrationReport passingReport(String fingerprint) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 12, 0);
        return new StageBMigrationReport(true, now, now, fingerprint, fingerprint,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                List.<StageBMigrationMismatch>of());
    }

    private static StageBMigrationReport failingReport(String fingerprint) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 12, 1);
        return new StageBMigrationReport(false, now, now, fingerprint, fingerprint,
                0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1,
                List.of(new StageBMigrationMismatch("UNRESOLVED_MIGRATION_ISSUE", 1L, "test")));
    }

    private String[] operatorArguments(String action, String operatorIdentity) {
        return new String[]{
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(),
                "--metro.article.rollout-build.binary-generation="
                        + buildA.binaryGeneration(),
                "--metro.article.rollout-build.schema-generation="
                        + buildA.schemaGeneration(),
                "--metro.article.rollout-build.digest=" + buildA.buildDigest(),
                "--metro.migration.stage-b.operator-identity=" + operatorIdentity,
                "--metro.article.rollout-operator.action=" + action
        };
    }

    private void runDocumentedRolloutAction(String action, String... actionProperties) {
        ArrayList<String> arguments = new ArrayList<>(List.of(
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(),
                "--METRO_ARTICLE_ROLLOUT_BINARY_GENERATION="
                        + buildA.binaryGeneration(),
                "--METRO_ARTICLE_ROLLOUT_SCHEMA_GENERATION="
                        + buildA.schemaGeneration(),
                "--METRO_ARTICLE_ROLLOUT_BUILD_DIGEST=" + buildA.buildDigest(),
                "--METRO_STAGE_B_OPERATOR_IDENTITY=operator-doc",
                "--METRO_STAGE_B_ROLLOUT_ACTION=" + action));
        for (String property : actionProperties) {
            arguments.add("--" + property);
        }
        StageBRolloutOperatorApplication.run(arguments.toArray(String[]::new));
    }

    private void createEmptyFingerprintTables() {
        jdbc.execute("DROP TABLE IF EXISTS article_revision_migration_issue");
        jdbc.execute("DROP TABLE IF EXISTS article_tag");
        jdbc.execute("DROP TABLE IF EXISTS tag");
        jdbc.execute("DROP TABLE IF EXISTS article_moderation_attempt");
        jdbc.execute("DROP TABLE IF EXISTS article_moderation_job");
        jdbc.execute("DROP TABLE IF EXISTS article_revision");
        jdbc.execute("DROP TABLE IF EXISTS article_draft");
        jdbc.execute("DROP TABLE IF EXISTS article");
        jdbc.execute("""
                CREATE TABLE article (
                    id BIGINT PRIMARY KEY,title TEXT,summary TEXT,content MEDIUMTEXT,cover TEXT,
                    author_id BIGINT,status INT,is_deleted INT,update_time DATETIME(6),
                    latest_revision_id BIGINT,pending_revision_id BIGINT,published_revision_id BIGINT,
                    visibility_state VARCHAR(24),review_state VARCHAR(24),
                    lifecycle_epoch BIGINT,lock_version BIGINT
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE article_draft (
                    article_id BIGINT PRIMARY KEY,user_id BIGINT,draft_version BIGINT,title TEXT,
                    summary TEXT,body_markdown MEDIUMTEXT,body_plain MEDIUMTEXT,cover TEXT,
                    tags_json JSON,content_hash CHAR(64),created_at DATETIME(6),
                    updated_at DATETIME(6),lock_version BIGINT
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE article_revision (
                    id BIGINT PRIMARY KEY,article_id BIGINT,revision_no BIGINT,title TEXT,summary TEXT,
                    body_markdown MEDIUMTEXT,body_plain MEDIUMTEXT,cover TEXT,tags_json JSON,
                    content_hash CHAR(64),source_draft_version BIGINT,created_by BIGINT,
                    created_at DATETIME(6)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE article_moderation_job (
                    id BIGINT PRIMARY KEY,article_id BIGINT,revision_id BIGINT,content_hash CHAR(64),
                    state VARCHAR(32),model_decision VARCHAR(16),risk_score DECIMAL(6,5),
                    policy_hits_json JSON,attempt_count INT,next_attempt_at DATETIME(6),lease_owner TEXT,
                    lease_until DATETIME(6),last_error TEXT,reviewer_id BIGINT,review_reason TEXT,
                    reviewed_at DATETIME(6),created_at DATETIME(6),updated_at DATETIME(6),
                    lock_version BIGINT
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE article_moderation_attempt (
                    id BIGINT PRIMARY KEY,job_id BIGINT,attempt_no INT,provider VARCHAR(32),
                    model VARCHAR(96),prompt_version VARCHAR(32),input_hash CHAR(64),
                    structured_output_json JSON,latency_ms BIGINT,token_usage_json JSON,
                    finish_reason VARCHAR(32),error_code VARCHAR(64),created_at DATETIME(6)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE tag (
                    id BIGINT PRIMARY KEY,name VARCHAR(50) CHARACTER SET utf8mb4
                        COLLATE utf8mb4_0900_bin
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE article_tag (
                    id BIGINT PRIMARY KEY,article_id BIGINT,tag_id BIGINT
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE article_revision_migration_issue (
                    id BIGINT PRIMARY KEY,article_id BIGINT,issue_code VARCHAR(64),
                    observed_hash CHAR(64),details_json JSON,detected_at DATETIME(6),
                    resolved_at DATETIME(6),resolution_note VARCHAR(500)
                ) ENGINE=InnoDB
                """);
    }

    private static void restrictToOwner(Path path) throws Exception {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
        catch (UnsupportedOperationException ignored) {
            // Production still applies the absolute/no-symlink/type/size checks cross-platform.
        }
    }

    private String[] migrationArguments(String action, String operatorIdentity,
                                        String verificationReportPath,
                                        ArticleRevisionMode declaredMode) {
        java.util.ArrayList<String> arguments = new java.util.ArrayList<>(List.of(
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(),
                "--spring.elasticsearch.uris=http://127.0.0.1:1",
                "--metro.article.rollout-build.binary-generation="
                        + buildA.binaryGeneration(),
                "--metro.article.rollout-build.schema-generation="
                        + buildA.schemaGeneration(),
                "--metro.article.rollout-build.digest=" + buildA.buildDigest(),
                "--metro.migration.stage-b.operator-identity=" + operatorIdentity,
                "--metro.migration.stage-b.action=" + action));
        if (declaredMode != null) {
            arguments.add("--metro.article.revision-mode=" + declaredMode);
        }
        if (verificationReportPath != null) {
            arguments.add("--metro.migration.stage-b.verification-report-path="
                    + verificationReportPath);
        }
        return arguments.toArray(String[]::new);
    }

    private StageBMigrationRunner runner(StageBMigrationAction action,
                                         ArticleRevisionModeResolver modeResolver,
                                         StageBArticleMigrationService migration,
                                         cumt.zongzuo.community.article.migration.StageBArticleMigrationVerifier verifier) {
        StageBMigrationProperties properties = new StageBMigrationProperties();
        properties.setAction(action);
        properties.setOperatorIdentity("operator-a");
        StageBVerificationArtifactWriter artifactWriter =
                mock(StageBVerificationArtifactWriter.class);
        when(artifactWriter.write(any(StageBMigrationReport.class),
                any(StageBVerificationRun.class), any(ArticleRevisionBuildIdentity.class),
                any(String.class))).thenAnswer(invocation -> {
                    StageBMigrationReport report = invocation.getArgument(0);
                    StageBVerificationRun run = invocation.getArgument(1);
                    ArticleRevisionBuildIdentity identity = invocation.getArgument(2);
                    String operatorIdentity = invocation.getArgument(3);
                    return new StageBVerificationArtifact(1, report, identity, run,
                            operatorIdentity, run.checkpointVersion() + 1,
                            StageBMigrationReportHasher.hash(report));
                });
        return new StageBMigrationRunner(properties, modeResolver, migration, verifier,
                operator, buildA, artifactWriter);
    }

    private static void assertPublishedEditRejected(ArticleMutationGate gate, String reason) {
        assertThatThrownBy(gate::requirePublishedRevisionEditAllowed)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(reason);
    }

    private static void assertWriteFenced(ArticleMutationGate gate) {
        assertThatThrownBy(gate::requireArticleWriteAllowed)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error)
                        .getStatusCode().value()).isEqualTo(503))
                .hasMessageContaining("ARTICLE_CUTOVER_IN_PROGRESS");
        assertThatThrownBy(gate::requirePublishedRevisionEditAllowed)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error)
                        .getStatusCode().value()).isEqualTo(503))
                .hasMessageContaining("ARTICLE_CUTOVER_IN_PROGRESS");
    }

    private static final class RecordingMigrationService implements StageBArticleMigrationService {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public MigrationBatchResult backfillAfter(long afterArticleId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MigrationRunResult backfillAll(int batchSize) {
            calls.incrementAndGet();
            return new MigrationRunResult(0, 0, 0, 0, 0);
        }

        int calls() {
            return calls.get();
        }
    }

    private AnnotationConfigApplicationContext startResolverContext(
            ArticleRevisionMode mode, ArticleRevisionBuildIdentity identity) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        try {
            ArticleRevisionProperties properties = new ArticleRevisionProperties();
            properties.setRevisionMode(mode);
            context.registerBean(ArticleRevisionProperties.class, () -> properties);
            context.registerBean(ArticleRevisionBuildIdentity.class, () -> identity);
            context.registerBean(StageBRolloutCheckpointReader.class,
                    () -> new JdbcStageBRolloutCheckpointReader(jdbc));
            context.registerBean(StageBRolloutStartupGate.class,
                    () -> new JdbcStageBRolloutStartupGate(
                            context.getBean(StageBRolloutCheckpointReader.class)));
            context.registerBean(ConfiguredArticleRevisionModeResolver.class);
            context.refresh();
            return context;
        }
        catch (RuntimeException startupFailure) {
            context.close();
            throw startupFailure;
        }
    }
}
