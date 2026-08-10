package cumt.zongzuo.community.article.rollout;

import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.migration.StageBArticleFingerprintService;
import cumt.zongzuo.community.article.migration.StageBMigrationReport;
import cumt.zongzuo.community.article.migration.StageBMigrationReportHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

@Service
public final class StageBRolloutOperator {

    private final JdbcTemplate jdbc;
    private final StageBRolloutCheckpointReader checkpointReader;
    private final StageBArticleFingerprintService fingerprintService;
    private final TransactionTemplate transactions;

    @Autowired
    public StageBRolloutOperator(JdbcTemplate jdbc,
                                 StageBRolloutCheckpointReader checkpointReader,
                                 StageBArticleFingerprintService fingerprintService,
                                 PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.checkpointReader = checkpointReader;
        this.fingerprintService = fingerprintService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    StageBRolloutOperator(JdbcTemplate jdbc,
                          StageBRolloutCheckpointReader checkpointReader,
                          StageBArticleFingerprintService fingerprintService) {
        this(jdbc, checkpointReader, fingerprintService,
                new DataSourceTransactionManager(Objects.requireNonNull(
                        jdbc.getDataSource(), "rollout checkpoint DataSource")));
    }

    public StageBRolloutCheckpoint bootstrapLegacy(ArticleRevisionBuildIdentity identity,
                                                   String operator) {
        return transaction(() -> {
            validateOperator(operator);
            if (checkpointReader.find().isPresent()) {
                throw new IllegalStateException("article revision rollout checkpoint already exists");
            }
            try {
                int inserted = jdbc.update("""
                        INSERT INTO article_revision_rollout_checkpoint
                            (checkpoint_id,mode,schema_generation,minimum_binary_generation,
                             required_build_digest,cutover_epoch,updated_by,updated_at,lock_version)
                        VALUES (1,'LEGACY',?,?,?,?,?,CURRENT_TIMESTAMP(6),0)
                        """, identity.schemaGeneration(), identity.binaryGeneration(),
                        identity.buildDigest(), 0, operator);
                if (inserted != 1) {
                    throw conflict();
                }
            }
            catch (DuplicateKeyException concurrentBootstrap) {
                throw conflict();
            }
            return checkpointReader.require();
        });
    }

    public StageBRolloutCheckpoint markBackfillStarted(ArticleRevisionBuildIdentity identity,
                                                       String operator) {
        return transaction(() -> {
            validateOperator(operator);
            StageBRolloutCheckpoint checkpoint = checkpointReader.requireForUpdate();
            requireAuthorized(checkpoint, identity);
            if (checkpoint.mode() != ArticleRevisionMode.SHADOW
                    && checkpoint.mode() != ArticleRevisionMode.VERIFY_FENCE) {
                throw new IllegalStateException("backfill can start only in SHADOW or VERIFY_FENCE");
            }
            if (checkpoint.backfillStartedAt() != null) {
                return checkpoint;
            }
            exactUpdate(jdbc.update("""
                    UPDATE article_revision_rollout_checkpoint
                    SET backfill_started_at=CURRENT_TIMESTAMP(6),updated_by=?,
                        updated_at=CURRENT_TIMESTAMP(6),lock_version=lock_version+1
                    WHERE checkpoint_id=1 AND mode=? AND lock_version=?
                      AND backfill_started_at IS NULL
                    """, operator, checkpoint.mode().name(), checkpoint.lockVersion()));
            return checkpointReader.require();
        });
    }

    public StageBRolloutCheckpoint recordVerificationPass(StageBMigrationReport report,
                                                           StageBVerificationRun run,
                                                           ArticleRevisionBuildIdentity identity,
                                                           String operator) {
        StageBRolloutCheckpoint checkpoint = recordVerificationResult(
                report, run, identity, operator);
        if (checkpoint.verifiedAt() == null) {
            throw new IllegalStateException("Stage B verification result did not pass");
        }
        return checkpoint;
    }

    /**
     * Durably invalidates every prior verification proof before a new verification run starts.
     * This transaction intentionally commits independently from the backfill/verifier work so a
     * crashed verifier cannot leave an older PASS eligible for promotion.
     */
    public StageBVerificationRun beginVerification(ArticleRevisionBuildIdentity identity,
                                                   String operator) {
        return transaction(() -> {
            validateOperator(operator);
            StageBRolloutCheckpoint checkpoint = checkpointReader.requireForUpdate();
            requireAuthorized(checkpoint, identity);
            if (checkpoint.mode() != ArticleRevisionMode.VERIFY_FENCE) {
                throw new IllegalStateException("verification can begin only in VERIFY_FENCE");
            }
            exactUpdate(jdbc.update("""
                    UPDATE article_revision_rollout_checkpoint
                    SET verified_build_digest=NULL,verified_fingerprint=NULL,
                        verify_report_hash=NULL,verified_at=NULL,
                        sentinel_build_digest=NULL,sentinel_report_hash=NULL,
                        sentinel_verified_at=NULL,updated_by=?,updated_at=CURRENT_TIMESTAMP(6),
                        lock_version=lock_version+1
                    WHERE checkpoint_id=1 AND mode='VERIFY_FENCE' AND lock_version=?
                    """, operator, checkpoint.lockVersion()));
            StageBRolloutCheckpoint begun = checkpointReader.require();
            return new StageBVerificationRun(begun.lockVersion(), identity.buildDigest());
        });
    }

    public StageBRolloutCheckpoint recordVerificationResult(StageBMigrationReport report,
                                                             StageBVerificationRun run,
                                                             ArticleRevisionBuildIdentity identity,
                                                             String operator) {
        return transaction(() -> {
            validateOperator(operator);
            Objects.requireNonNull(run, "run");
            StageBRolloutCheckpoint checkpoint = checkpointReader.requireForUpdate();
            requireAuthorized(checkpoint, identity);
            if (checkpoint.mode() != ArticleRevisionMode.VERIFY_FENCE) {
                throw new IllegalStateException("verification proof requires VERIFY_FENCE");
            }
            if (!run.buildDigest().equals(identity.buildDigest())
                    || checkpoint.lockVersion() != run.checkpointVersion()) {
                throw conflict();
            }
            Objects.requireNonNull(report, "report");
            boolean passing = isPassingReport(report)
                    && report.endFingerprint().matches("[0-9a-f]{64}")
                    && report.endFingerprint().equals(fingerprintService.fingerprint());
            if (passing) {
                String reportHash = StageBMigrationReportHasher.hash(report);
                exactUpdate(jdbc.update("""
                        UPDATE article_revision_rollout_checkpoint
                        SET verified_build_digest=?,verified_fingerprint=?,verify_report_hash=?,
                            verified_at=CURRENT_TIMESTAMP(6),
                            sentinel_build_digest=NULL,sentinel_report_hash=NULL,
                            sentinel_verified_at=NULL,updated_by=?,updated_at=CURRENT_TIMESTAMP(6),
                            lock_version=lock_version+1
                        WHERE checkpoint_id=1 AND mode='VERIFY_FENCE' AND lock_version=?
                          AND required_build_digest=?
                        """, identity.buildDigest(), report.endFingerprint(), reportHash,
                        operator, run.checkpointVersion(), run.buildDigest()));
            }
            else {
                exactUpdate(jdbc.update("""
                        UPDATE article_revision_rollout_checkpoint
                        SET verified_build_digest=NULL,verified_fingerprint=NULL,
                            verify_report_hash=NULL,verified_at=NULL,
                            sentinel_build_digest=NULL,sentinel_report_hash=NULL,
                            sentinel_verified_at=NULL,updated_by=?,updated_at=CURRENT_TIMESTAMP(6),
                            lock_version=lock_version+1
                        WHERE checkpoint_id=1 AND mode='VERIFY_FENCE' AND lock_version=?
                          AND required_build_digest=?
                        """, operator, run.checkpointVersion(), run.buildDigest()));
            }
            return checkpointReader.require();
        });
    }

    /**
     * Invalidates any prior sentinel proof before the pointer-read checks execute.
     */
    public StageBPointerSentinelRun beginPointerSentinel(
            ArticleRevisionBuildIdentity identity, String operator) {
        return transaction(() -> {
            validateOperator(operator);
            StageBRolloutCheckpoint checkpoint = checkpointReader.requireForUpdate();
            requireAuthorized(checkpoint, identity);
            if (checkpoint.mode() != ArticleRevisionMode.POINTER_READ) {
                throw new IllegalStateException("pointer sentinel requires POINTER_READ");
            }
            requireFreshVerifiedFingerprint(checkpoint);
            exactUpdate(jdbc.update("""
                    UPDATE article_revision_rollout_checkpoint
                    SET sentinel_build_digest=NULL,sentinel_report_hash=NULL,
                        sentinel_verified_at=NULL,updated_by=?,
                        updated_at=CURRENT_TIMESTAMP(6),lock_version=lock_version+1
                    WHERE checkpoint_id=1 AND mode='POINTER_READ' AND lock_version=?
                    """, operator, checkpoint.lockVersion()));
            StageBRolloutCheckpoint begun = checkpointReader.require();
            return new StageBPointerSentinelRun(begun.lockVersion(),
                    identity.buildDigest(), begun.verifiedFingerprint());
        });
    }

    public StageBRolloutCheckpoint recordPointerSentinelResult(
            StageBPointerSentinelReport report,
            ArticleRevisionBuildIdentity identity,
            String operator) {
        return transaction(() -> {
            validateOperator(operator);
            Objects.requireNonNull(report, "report");
            StageBRolloutCheckpoint checkpoint = checkpointReader.requireForUpdate();
            requireAuthorized(checkpoint, identity);
            if (checkpoint.mode() != ArticleRevisionMode.POINTER_READ) {
                throw new IllegalStateException("pointer sentinel requires POINTER_READ");
            }
            if (!report.buildDigest().equals(identity.buildDigest())
                    || !report.buildDigest().equals(checkpoint.requiredBuildDigest())
                    || !report.verifiedFingerprint().equals(checkpoint.verifiedFingerprint())) {
                throw new IllegalStateException(
                        "pointer sentinel report is not bound to the active build and fingerprint");
            }
            if (checkpoint.lockVersion() != report.checkpointVersion()) {
                throw conflict();
            }
            requireFreshVerifiedFingerprint(checkpoint);
            int updated;
            if (report.passed()) {
                updated = jdbc.update("""
                        UPDATE article_revision_rollout_checkpoint
                        SET sentinel_build_digest=?,sentinel_report_hash=?,
                            sentinel_verified_at=CURRENT_TIMESTAMP(6),updated_by=?,
                            updated_at=CURRENT_TIMESTAMP(6),lock_version=lock_version+1
                        WHERE checkpoint_id=1 AND mode='POINTER_READ' AND lock_version=?
                          AND required_build_digest=? AND verified_fingerprint=?
                        """, report.buildDigest(), report.reportHash(), operator,
                        report.checkpointVersion(), report.buildDigest(),
                        report.verifiedFingerprint());
            }
            else {
                updated = jdbc.update("""
                        UPDATE article_revision_rollout_checkpoint
                        SET sentinel_build_digest=NULL,sentinel_report_hash=NULL,
                            sentinel_verified_at=NULL,updated_by=?,
                            updated_at=CURRENT_TIMESTAMP(6),lock_version=lock_version+1
                        WHERE checkpoint_id=1 AND mode='POINTER_READ' AND lock_version=?
                          AND required_build_digest=? AND verified_fingerprint=?
                        """, operator, report.checkpointVersion(), report.buildDigest(),
                        report.verifiedFingerprint());
            }
            exactUpdate(updated);
            return checkpointReader.require();
        });
    }

    public StageBRolloutCheckpoint authorizeBuild(ArticleRevisionBuildIdentity targetIdentity,
                                                  ArticleRevisionBuildIdentity currentIdentity,
                                                  String operator) {
        return transaction(() -> {
            validateOperator(operator);
            StageBRolloutCheckpoint checkpoint = checkpointReader.requireForUpdate();
            requireAuthorized(checkpoint, currentIdentity);
            if (checkpoint.mode() != ArticleRevisionMode.POINTER_READ) {
                throw new IllegalStateException("forward-fix build authorization requires POINTER_READ");
            }
            requireFreshVerifiedFingerprint(checkpoint);
            if (targetIdentity.schemaGeneration() != checkpoint.schemaGeneration()) {
                throw new IllegalStateException(
                        "schema generation changes require a new migration and VERIFY cycle");
            }
            if (targetIdentity.binaryGeneration() < checkpoint.minimumBinaryGeneration()) {
                throw new IllegalStateException("forward-fix binary generation cannot move backward");
            }
            exactUpdate(jdbc.update("""
                    UPDATE article_revision_rollout_checkpoint
                    SET schema_generation=?,minimum_binary_generation=?,required_build_digest=?,
                        sentinel_build_digest=NULL,sentinel_report_hash=NULL,
                        sentinel_verified_at=NULL,updated_by=?,updated_at=CURRENT_TIMESTAMP(6),
                        lock_version=lock_version+1
                    WHERE checkpoint_id=1 AND mode='POINTER_READ' AND lock_version=?
                    """, targetIdentity.schemaGeneration(), targetIdentity.binaryGeneration(),
                    targetIdentity.buildDigest(), operator, checkpoint.lockVersion()));
            return checkpointReader.require();
        });
    }

    public StageBRolloutCheckpoint transitionTo(ArticleRevisionMode target,
                                                ArticleRevisionBuildIdentity identity,
                                                String operator) {
        return transaction(() -> {
            validateOperator(operator);
            Objects.requireNonNull(target, "target");
            StageBRolloutCheckpoint checkpoint = checkpointReader.requireForUpdate();
            requireAuthorized(checkpoint, identity);
            validateTransition(checkpoint, target);
            long cutoverEpoch = checkpoint.cutoverEpoch()
                    + (target == ArticleRevisionMode.CUTOVER ? 1 : 0);
            boolean clearProof = target == ArticleRevisionMode.VERIFY_FENCE;
            int updated;
            if (clearProof) {
                updated = jdbc.update("""
                        UPDATE article_revision_rollout_checkpoint
                        SET mode=?,verified_build_digest=NULL,verified_fingerprint=NULL,
                            verify_report_hash=NULL,verified_at=NULL,
                            sentinel_build_digest=NULL,sentinel_report_hash=NULL,
                            sentinel_verified_at=NULL,updated_by=?,updated_at=CURRENT_TIMESTAMP(6),
                            lock_version=lock_version+1
                        WHERE checkpoint_id=1 AND mode=? AND lock_version=?
                        """, target.name(), operator, checkpoint.mode().name(),
                        checkpoint.lockVersion());
            }
            else {
                updated = jdbc.update("""
                        UPDATE article_revision_rollout_checkpoint
                        SET mode=?,cutover_epoch=?,updated_by=?,updated_at=CURRENT_TIMESTAMP(6),
                            lock_version=lock_version+1
                        WHERE checkpoint_id=1 AND mode=? AND lock_version=?
                        """, target.name(), cutoverEpoch, operator,
                        checkpoint.mode().name(), checkpoint.lockVersion());
            }
            exactUpdate(updated);
            return checkpointReader.require();
        });
    }

    public StageBRolloutCheckpoint emergencyFence(ArticleRevisionBuildIdentity identity,
                                                  String operator) {
        return transaction(() -> {
            validateOperator(operator);
            StageBRolloutCheckpoint checkpoint = checkpointReader.requireForUpdate();
            requireAuthorized(checkpoint, identity);
            if (checkpoint.mode() != ArticleRevisionMode.CUTOVER || checkpoint.cutoverEpoch() == 0) {
                throw new IllegalStateException("emergency fence requires an established CUTOVER");
            }
            exactUpdate(jdbc.update("""
                    UPDATE article_revision_rollout_checkpoint
                    SET mode='POINTER_READ',sentinel_build_digest=NULL,sentinel_report_hash=NULL,
                        sentinel_verified_at=NULL,updated_by=?,updated_at=CURRENT_TIMESTAMP(6),
                        lock_version=lock_version+1
                    WHERE checkpoint_id=1 AND mode='CUTOVER' AND cutover_epoch>0 AND lock_version=?
                    """, operator, checkpoint.lockVersion()));
            return checkpointReader.require();
        });
    }

    private void validateTransition(StageBRolloutCheckpoint checkpoint,
                                    ArticleRevisionMode target) {
        if (checkpoint.cutoverEpoch() > 0
                && target != ArticleRevisionMode.POINTER_READ
                && target != ArticleRevisionMode.CUTOVER) {
            throw new IllegalStateException("article revision cutover is irreversible");
        }
        switch (checkpoint.mode()) {
            case LEGACY -> {
                if (target != ArticleRevisionMode.SHADOW) {
                    throw illegalTransition(checkpoint.mode(), target);
                }
            }
            case SHADOW -> {
                if (target == ArticleRevisionMode.LEGACY) {
                    if (checkpoint.backfillStartedAt() != null) {
                        throw new IllegalStateException(
                                "SHADOW cannot roll back after backfill has started");
                    }
                }
                else if (target == ArticleRevisionMode.VERIFY_FENCE) {
                    if (checkpoint.backfillStartedAt() == null) {
                        throw new IllegalStateException(
                                "VERIFY_FENCE requires durable backfill-started proof");
                    }
                }
                else {
                    throw illegalTransition(checkpoint.mode(), target);
                }
            }
            case VERIFY_FENCE -> {
                if (target != ArticleRevisionMode.POINTER_READ) {
                    throw illegalTransition(checkpoint.mode(), target);
                }
                requireVerificationProof(checkpoint);
                if (!checkpoint.verifiedBuildDigest().equals(checkpoint.requiredBuildDigest())) {
                    throw new IllegalStateException(
                            "first POINTER_READ requires verification by the authorized build");
                }
                requireFreshVerifiedFingerprint(checkpoint);
            }
            case POINTER_READ -> {
                if (target != ArticleRevisionMode.CUTOVER) {
                    throw illegalTransition(checkpoint.mode(), target);
                }
                requireVerificationProof(checkpoint);
                if (checkpoint.sentinelVerifiedAt() == null
                        || !checkpoint.requiredBuildDigest().equals(
                        checkpoint.sentinelBuildDigest())) {
                    throw new IllegalStateException(
                            "CUTOVER requires a sentinel from the authorized build");
                }
                requireFreshVerifiedFingerprint(checkpoint);
            }
            case CUTOVER -> throw new IllegalStateException(
                    "CUTOVER can move only through the explicit emergency fence");
        }
    }

    private void requireFreshVerifiedFingerprint(StageBRolloutCheckpoint checkpoint) {
        requireVerificationProof(checkpoint);
        String current = fingerprintService.fingerprint();
        if (!checkpoint.verifiedFingerprint().equals(current)) {
            throw new IllegalStateException(
                    "current article fingerprint differs from the verified fingerprint");
        }
    }

    private static void requireVerificationProof(StageBRolloutCheckpoint checkpoint) {
        if (checkpoint.verifiedAt() == null
                || checkpoint.verifiedBuildDigest() == null
                || checkpoint.verifiedFingerprint() == null
                || checkpoint.verifyReportHash() == null) {
            throw new IllegalStateException("durable verification proof is missing");
        }
    }

    private static boolean isPassingReport(StageBMigrationReport report) {
        return report.passed()
                && report.mismatchCount() == 0
                && report.unresolvedIssueArticleCount() == 0
                && report.startFingerprint() != null
                && report.startFingerprint().equals(report.endFingerprint());
    }

    private static void requireAuthorized(StageBRolloutCheckpoint checkpoint,
                                          ArticleRevisionBuildIdentity identity) {
        if (identity.schemaGeneration() != checkpoint.schemaGeneration()) {
            throw new IllegalStateException(
                    "operator schema generation does not match checkpoint");
        }
        if (identity.binaryGeneration() < checkpoint.minimumBinaryGeneration()) {
            throw new IllegalStateException("operator binary generation is stale");
        }
        if (!identity.buildDigest().equals(checkpoint.requiredBuildDigest())) {
            throw new IllegalStateException("operator build digest is not authorized");
        }
    }

    private static void validateOperator(String operator) {
        if (operator == null || !operator.matches("[A-Za-z0-9._:@/-]{1,96}")) {
            throw new IllegalArgumentException("operator identity is invalid");
        }
    }

    private static IllegalStateException illegalTransition(ArticleRevisionMode from,
                                                           ArticleRevisionMode to) {
        return new IllegalStateException("illegal article revision rollout transition "
                + from + " -> " + to);
    }

    private static IllegalStateException conflict() {
        return new IllegalStateException("article revision rollout checkpoint CAS conflict");
    }

    private static void exactUpdate(int updated) {
        if (updated != 1) {
            throw conflict();
        }
    }

    private <T> T transaction(Supplier<T> work) {
        T result = transactions.execute(status -> work.get());
        return Objects.requireNonNull(result, "rollout transaction result");
    }
}
