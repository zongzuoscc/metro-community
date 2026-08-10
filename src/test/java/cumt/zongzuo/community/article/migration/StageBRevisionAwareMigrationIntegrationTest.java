package cumt.zongzuo.community.article.migration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.ai.moderation.revision.ArticleModerationDecisionService;
import cumt.zongzuo.community.ai.moderation.web.ModerationDecisionRequest;
import cumt.zongzuo.community.ai.moderation.web.ModerationJobResponse;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.article.rollout.ArticleRevisionBuildIdentity;
import cumt.zongzuo.community.article.rollout.StageBRolloutOperator;
import cumt.zongzuo.community.article.service.ArticleContentCanonicalizer;
import cumt.zongzuo.community.article.service.ArticleMutationFacade;
import cumt.zongzuo.community.article.model.ArticleDraft;
import cumt.zongzuo.community.article.web.SaveArticleDraftCommand;
import cumt.zongzuo.community.article.web.SubmissionResult;
import cumt.zongzuo.community.article.web.SubmitArticleRevisionCommand;
import cumt.zongzuo.community.repository.ArticleRepository;
import cumt.zongzuo.community.document.ArticleDoc;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = "metro.article.revision-mode=SHADOW")
class StageBRevisionAwareMigrationIntegrationTest extends IntegrationTestSupport {

    private static final long AUTHOR_ID = 94_800L;

    @Autowired
    private ArticleMutationFacade mutationFacade;

    @Autowired
    private ArticleModerationDecisionService decisionService;

    @Autowired
    private StageBArticleMigrationService migrationService;

    @Autowired
    private ArticleContentCanonicalizer canonicalizer;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private RestClient elasticsearchRestClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StageBArticleFingerprintService fingerprintService;

    @Autowired
    private StageBRolloutOperator rolloutOperator;

    @Autowired
    private ArticleRevisionBuildIdentity buildIdentity;

    @Autowired
    private ArticleRepository articleRepository;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void resetFixture() {
        articleRepository.deleteAll();
        ensureArticleReadAlias();
        refreshArticleIndex();
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='ARTICLE'");
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL");
        jdbcTemplate.update("DELETE FROM article_moderation_attempt");
        jdbcTemplate.update("DELETE FROM article_moderation_job");
        jdbcTemplate.update("DELETE FROM article_revision_migration_issue");
        jdbcTemplate.update("DELETE FROM article_revision");
        jdbcTemplate.update("DELETE FROM article_draft");
        jdbcTemplate.update("DELETE FROM article_tag");
        jdbcTemplate.update("DELETE FROM article");
        jdbcTemplate.update("DELETE FROM article_revision_rollout_checkpoint");
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status)
                VALUES(?, 'revision-aware-author', 'unused',
                       'revision-aware-author@example.com', 0, 0)
                ON DUPLICATE KEY UPDATE status=0
                """, AUTHOR_ID);
    }

    @Test
    void nativePendingWithFutureDraftPassesTheRealFinalBackfillAndVerify() throws Exception {
        var draft = mutationFacade.saveDraft(new SaveArticleDraftCommand(
                null, 0, "frozen-v1", "summary-v1", "body-v1", "cover-v1", List.of("java")),
                AUTHOR_ID);
        SubmissionResult submitted = mutationFacade.submit(new SubmitArticleRevisionCommand(
                draft.getArticleId(), AUTHOR_ID, draft.getDraftVersion()));
        mutationFacade.saveDraft(new SaveArticleDraftCommand(
                draft.getArticleId(), draft.getDraftVersion(), "future-v2", "summary-v2",
                "body-v2", "cover-v2", List.of("future")), AUTHOR_ID);

        Map<String, Object> articleBefore = articleState(submitted.articleId());
        Map<String, Object> draftBefore = draftState(submitted.articleId());
        Map<String, Object> revisionBefore = revisionState(submitted.revisionId());
        Map<String, Object> jobBefore = jobState(submitted.moderationJobId());

        StageBMigrationReport report = runProductionVerify("native-pending.json");

        assertThat(report.passed()).as(report.toString()).isTrue();
        assertThat(report.mismatches()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM article_revision_migration_issue
                WHERE article_id=? AND resolved_at IS NULL
                """, Integer.class, submitted.articleId())).isZero();
        assertThat(articleState(submitted.articleId())).isEqualTo(articleBefore);
        assertThat(draftState(submitted.articleId())).isEqualTo(draftBefore);
        assertThat(revisionState(submitted.revisionId())).isEqualTo(revisionBefore);
        assertThat(jobState(submitted.moderationJobId())).isEqualTo(jobBefore);
    }

    @Test
    void rejectedRevisionCanReturnToDraftWithoutLeavingAStaleLatestPointer() {
        var draft = mutationFacade.saveDraft(new SaveArticleDraftCommand(
                null, 0, "rejected-v1", "summary-v1", "body-v1", "cover-v1", List.of()),
                AUTHOR_ID);
        SubmissionResult rejected = mutationFacade.submit(new SubmitArticleRevisionCommand(
                draft.getArticleId(), AUTHOR_ID, draft.getDraftVersion()));
        ModerationJobResponse pending = decisionService.get(rejected.moderationJobId());
        decisionService.reject(pending.id(), new ModerationDecisionRequest(
                pending.revisionId(), pending.jobVersion(), pending.articleVersion(),
                "needs another draft"), AUTHOR_ID);

        var future = mutationFacade.saveDraft(new SaveArticleDraftCommand(
                draft.getArticleId(), draft.getDraftVersion(), "resubmit-v2", "summary-v2",
                "body-v2", "cover-v2", List.of("second")), AUTHOR_ID);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT status,visibility_state,review_state,latest_revision_id,
                       pending_revision_id,published_revision_id
                FROM article WHERE id=?
                """, draft.getArticleId()))
                .containsEntry("status", 0)
                .containsEntry("visibility_state", "PRIVATE")
                .containsEntry("review_state", "NOT_SUBMITTED")
                .containsEntry("latest_revision_id", null)
                .containsEntry("pending_revision_id", null)
                .containsEntry("published_revision_id", null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM article_moderation_job WHERE id=?",
                String.class, rejected.moderationJobId())).isEqualTo("HUMAN_REJECTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_revision WHERE article_id=?",
                Integer.class, draft.getArticleId())).isOne();

        SubmissionResult resubmitted = mutationFacade.submit(new SubmitArticleRevisionCommand(
                draft.getArticleId(), AUTHOR_ID, future.getDraftVersion()));
        assertThat(resubmitted.revisionNo()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status,review_state,latest_revision_id,pending_revision_id,
                       published_revision_id FROM article WHERE id=?
                """, draft.getArticleId()))
                .containsEntry("status", 2)
                .containsEntry("review_state", "HUMAN_PENDING")
                .containsEntry("latest_revision_id", resubmitted.revisionId())
                .containsEntry("pending_revision_id", resubmitted.revisionId())
                .containsEntry("published_revision_id", null);
        assertThat(runProductionVerify("reject-resubmit.json").passed()).isTrue();
    }

    @Test
    void taskSevenModelEvidenceRemainsByteStableThroughFinalBackfillAndVerify() {
        NativeSubmission nativeSubmission = submitNative("model-v1", "model-body", List.of("ai"));
        long jobId = nativeSubmission.submission().moderationJobId();
        jdbcTemplate.update("""
                UPDATE article_moderation_job
                SET state='HUMAN_PENDING',model_decision='PASS',risk_score=0.1250,
                    policy_hits_json=JSON_OBJECT('categories',JSON_ARRAY('SAFE'),
                                                 'severity',0,'confidence',0.99,
                                                 'uncertain',FALSE),
                    attempt_count=1,last_error=NULL,lease_owner=NULL,lease_until=NULL,
                    updated_at=NOW(6),lock_version=2
                WHERE id=?
                """, jobId);
        insertSuccessfulAttempt(nativeSubmission, 1,
                nativeSubmission.submission().contentHash());
        Map<String, Object> jobBefore = jobState(jobId);
        Map<String, Object> attemptBefore = attemptState(jobId);

        StageBMigrationReport report = runProductionVerify("model-evidence.json");

        assertThat(report.passed()).as(report.toString()).isTrue();
        assertThat(jobState(jobId)).isEqualTo(jobBefore);
        assertThat(attemptState(jobId)).isEqualTo(attemptBefore);
    }

    @Test
    void modelEvidenceRequiresASuccessfulBoundAttemptWithValidAuditFields() {
        NativeSubmission missing = submitNative("missing-attempt", "missing-attempt-body", List.of());
        installModelEvidence(missing.submission().moderationJobId(), 1);

        NativeSubmission outOfRange = submitNative("out-of-range-attempt", "range-body", List.of());
        installModelEvidence(outOfRange.submission().moderationJobId(), 1);
        insertSuccessfulAttempt(outOfRange, 2, outOfRange.submission().contentHash());

        NativeSubmission invalidHash = submitNative("invalid-attempt-hash", "hash-body", List.of());
        installModelEvidence(invalidHash.submission().moderationJobId(), 1);
        insertSuccessfulAttempt(invalidHash, 1, "not-a-64-character-lowercase-hex-hash");

        StageBMigrationReport report = runProductionVerifyExpectFailure(
                "invalid-model-attempt-evidence.json");

        assertThat(report.passed()).isFalse();
        assertThat(report.mismatches()).extracting(StageBMigrationMismatch::code)
                .contains("MODERATION_JOB_ATTEMPT_EVIDENCE_MISMATCH",
                        "MODERATION_ATTEMPT_SEQUENCE_MISMATCH",
                        "MODERATION_ATTEMPT_INTEGRITY_MISMATCH");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM article_revision_migration_issue
                WHERE article_id IN (?,?,?) AND issue_code='BACKFILL_MISMATCH'
                  AND resolved_at IS NULL
                """, Integer.class, missing.submission().articleId(),
                outOfRange.submission().articleId(), invalidHash.submission().articleId()))
                .isEqualTo(3);
    }

    @Test
    void approveAndRejectKeepTheirFutureDraftsWhileFinalVerificationUsesImmutablePointers() {
        NativeSubmission approval = submitNative(
                "approve-v1", "approved-public-body", List.of("approved"));
        ArticleDraft approvalFuture = mutationFacade.saveDraft(new SaveArticleDraftCommand(
                approval.submission().articleId(), approval.draft().getDraftVersion(),
                "approve-future-v2", "future-summary", "future-private-body", "future-cover",
                List.of("future")), AUTHOR_ID);
        decide(approval.submission(), true, "publish frozen v1");
        saveMatchingDocument(approval.submission().articleId());

        NativeSubmission rejection = submitNative(
                "reject-v1", "rejected-frozen-body", List.of("rejected"));
        ArticleDraft rejectionFuture = mutationFacade.saveDraft(new SaveArticleDraftCommand(
                rejection.submission().articleId(), rejection.draft().getDraftVersion(),
                "reject-future-v2", "future-summary", "future-rewrite-body", "future-cover",
                List.of("rewrite")), AUTHOR_ID);
        decide(rejection.submission(), false, "reject frozen v1");
        refreshArticleIndex();

        StageBMigrationReport report = runProductionVerify("decisions-with-future-drafts.json");

        assertThat(report.passed()).as(report.toString()).isTrue();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status,review_state,published_revision_id,pending_revision_id
                FROM article WHERE id=?
                """, approval.submission().articleId()))
                .containsEntry("status", 1)
                .containsEntry("review_state", "APPROVED")
                .containsEntry("published_revision_id", approval.submission().revisionId())
                .containsEntry("pending_revision_id", null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_markdown FROM article_draft WHERE article_id=?", String.class,
                approval.submission().articleId())).isEqualTo(approvalFuture.getBodyMarkdown());
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status,review_state,published_revision_id,pending_revision_id
                FROM article WHERE id=?
                """, rejection.submission().articleId()))
                .containsEntry("status", 3)
                .containsEntry("review_state", "REJECTED")
                .containsEntry("published_revision_id", null)
                .containsEntry("pending_revision_id", null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_markdown FROM article_draft WHERE article_id=?", String.class,
                rejection.submission().articleId())).isEqualTo(rejectionFuture.getBodyMarkdown());
    }

    @Test
    void rejectedReplacementKeepsTheNewerRejectedLatestAndOlderPublishedPointer() {
        NativeSubmission first = submitNative(
                "published-v1", "published-body-v1", List.of("replacement"));
        ArticleDraft replacementDraft = mutationFacade.saveDraft(new SaveArticleDraftCommand(
                first.submission().articleId(), first.draft().getDraftVersion(),
                "replacement-v2", "replacement-summary", "replacement-body-v2",
                "replacement-cover", List.of("replacement")), AUTHOR_ID);
        decide(first.submission(), true, "publish revision one");

        // This suite runs in SHADOW so published editing is intentionally gated. Bypass only
        // that mode precondition; the replacement submission and decision remain production paths.
        jdbcTemplate.update("UPDATE article SET status=0 WHERE id=?",
                first.submission().articleId());
        SubmissionResult replacement = mutationFacade.submit(new SubmitArticleRevisionCommand(
                first.submission().articleId(), AUTHOR_ID, replacementDraft.getDraftVersion()));
        decide(replacement, false, "keep the current publication");
        saveMatchingDocument(first.submission().articleId());
        refreshArticleIndex();

        StageBMigrationReport report = runProductionVerify("rejected-replacement.json");

        assertThat(report.passed()).as(report.toString()).isTrue();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status,visibility_state,review_state,latest_revision_id,
                       pending_revision_id,published_revision_id
                FROM article WHERE id=?
                """, first.submission().articleId()))
                .containsEntry("status", 1)
                .containsEntry("visibility_state", "PUBLIC")
                .containsEntry("review_state", "APPROVED")
                .containsEntry("latest_revision_id", replacement.revisionId())
                .containsEntry("pending_revision_id", null)
                .containsEntry("published_revision_id", first.submission().revisionId());
        assertThat(jdbcTemplate.queryForList("""
                SELECT state FROM article_moderation_job
                WHERE article_id=? ORDER BY id
                """, String.class, first.submission().articleId()))
                .containsExactly("HUMAN_APPROVED", "HUMAN_REJECTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_markdown FROM article_draft WHERE article_id=?", String.class,
                first.submission().articleId())).isEqualTo("replacement-body-v2");
    }

    @Test
    void finalVerificationRejectsAContradictoryDecisionForTheCurrentPublishedRevision() {
        NativeSubmission approved = submitNative(
                "approved-tuple", "approved-tuple-body", List.of("approved"));
        decide(approved.submission(), true, "approve the current immutable revision");
        saveMatchingDocument(approved.submission().articleId());
        jdbcTemplate.update("""
                UPDATE article_moderation_job SET state='HUMAN_REJECTED'
                WHERE id=?
                """, approved.submission().moderationJobId());
        refreshArticleIndex();

        StageBMigrationReport report = runProductionVerifyExpectFailure(
                "contradictory-human-decisions.json");

        assertThat(report.passed()).isFalse();
        assertThat(report.mismatches()).extracting(StageBMigrationMismatch::code)
                .contains("MODERATION_DECISION_TUPLE_MISMATCH");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM article_revision_migration_issue
                WHERE article_id=? AND issue_code='BACKFILL_MISMATCH'
                  AND resolved_at IS NULL
                """, Integer.class, approved.submission().articleId())).isOne();
    }

    @Test
    void finalVerificationRejectsAContradictoryDecisionForARecyclablePublishedPointer() {
        NativeSubmission approved = submitNative(
                "recyclable-publication", "recyclable-publication-body", List.of("recycled"));
        decide(approved.submission(), true, "publish before recycle");
        mutationFacade.recycle(approved.submission().articleId(), AUTHOR_ID);
        jdbcTemplate.update("""
                UPDATE article_moderation_job SET state='HUMAN_REJECTED'
                WHERE id=?
                """, approved.submission().moderationJobId());
        articleRepository.deleteById(approved.submission().articleId());
        refreshArticleIndex();

        StageBMigrationReport report = runProductionVerifyExpectFailure(
                "contradictory-recycled-publication.json");

        assertThat(report.passed()).isFalse();
        assertThat(report.mismatches()).extracting(StageBMigrationMismatch::code)
                .contains("MODERATION_DECISION_TUPLE_MISMATCH");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM article_revision_migration_issue
                WHERE article_id=? AND issue_code='BACKFILL_MISMATCH'
                  AND resolved_at IS NULL
                """, Integer.class, approved.submission().articleId())).isOne();
    }

    @Test
    void reportUnpublishMayKeepTheOriginalApprovedJobWhileFinalVerificationPasses() {
        NativeSubmission approved = submitNative(
                "reported-publication", "reported-publication-body", List.of("reported"));
        decide(approved.submission(), true, "publish before report confirmation");

        mutationFacade.rejectReportedArticle(approved.submission().articleId());
        articleRepository.deleteById(approved.submission().articleId());
        refreshArticleIndex();

        StageBMigrationReport report = runProductionVerify("reported-unpublish.json");

        assertThat(report.passed()).as(report.toString()).isTrue();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status,visibility_state,review_state,published_revision_id,
                       latest_revision_id,pending_revision_id
                FROM article WHERE id=?
                """, approved.submission().articleId()))
                .containsEntry("status", 3)
                .containsEntry("visibility_state", "PRIVATE")
                .containsEntry("review_state", "REJECTED")
                .containsEntry("published_revision_id", null)
                .containsEntry("latest_revision_id", approved.submission().revisionId())
                .containsEntry("pending_revision_id", null);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM article_moderation_job WHERE id=?
                """, String.class, approved.submission().moderationJobId()))
                .isEqualTo("HUMAN_APPROVED");
    }

    @Test
    void purgedPendingArticleKeepsImmutableAuditButNoActionableTupleAtFinalVerify() {
        NativeSubmission pending = submitNative("purge-v1", "purge-body", List.of());

        mutationFacade.recycle(pending.submission().articleId(), AUTHOR_ID);
        mutationFacade.purge(pending.submission().articleId(), AUTHOR_ID);
        Map<String, Object> articleBefore = articleState(pending.submission().articleId());
        Map<String, Object> jobBefore = jobState(pending.submission().moderationJobId());

        StageBMigrationReport report = runProductionVerify("purged.json");

        assertThat(report.passed()).as(report.toString()).isTrue();
        assertThat(articleBefore)
                .containsEntry("status", 0)
                .containsEntry("is_deleted", 1)
                .containsEntry("visibility_state", "PURGED")
                .containsEntry("review_state", "NOT_SUBMITTED")
                .containsEntry("latest_revision_id", null)
                .containsEntry("pending_revision_id", null)
                .containsEntry("published_revision_id", null);
        assertThat(jobBefore).containsEntry("state", "SUPERSEDED");
        assertThat(articleState(pending.submission().articleId())).isEqualTo(articleBefore);
        assertThat(jobState(pending.submission().moderationJobId())).isEqualTo(jobBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_revision WHERE article_id=?",
                Integer.class, pending.submission().articleId())).isOne();
    }

    @Test
    void finalBackfillAndVerifyFailClosedForRevisionHashAndLeaseDrift() {
        NativeSubmission pending = submitNative("tampered-v1", "original-body", List.of());
        jdbcTemplate.update("""
                UPDATE article_revision SET body_markdown='tampered-body'
                WHERE id=?
                """, pending.submission().revisionId());
        jdbcTemplate.update("""
                UPDATE article_moderation_job
                SET lease_owner='corrupt-owner',lease_until=DATE_ADD(NOW(6),INTERVAL 1 HOUR)
                WHERE id=?
                """, pending.submission().moderationJobId());

        StageBMigrationReport report = runProductionVerifyExpectFailure("drift.json");

        assertThat(report.passed()).isFalse();
        assertThat(report.mismatches()).extracting(StageBMigrationMismatch::code)
                .contains("REVISION_SELF_HASH_MISMATCH", "MODERATION_JOB_LEASE_MISMATCH",
                        "UNRESOLVED_MIGRATION_ISSUE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM article_revision_migration_issue
                WHERE article_id=? AND issue_code='BACKFILL_MISMATCH' AND resolved_at IS NULL
                """, Integer.class, pending.submission().articleId())).isOne();
    }

    private StageBMigrationReport runProductionVerify(String artifactName) {
        PreparedVerify prepared = prepareProductionVerify(artifactName);
        prepared.runner().run(new DefaultApplicationArguments());
        return readArtifact(prepared.properties());
    }

    private StageBMigrationReport runProductionVerifyExpectFailure(String artifactName) {
        PreparedVerify prepared = prepareProductionVerify(artifactName);
        assertThatThrownBy(() -> prepared.runner().run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stage B verification failed");
        return readArtifact(prepared.properties());
    }

    private PreparedVerify prepareProductionVerify(String artifactName) {
        jdbcTemplate.update("""
                INSERT INTO article_revision_rollout_checkpoint(
                    checkpoint_id,mode,schema_generation,minimum_binary_generation,
                    required_build_digest,backfill_started_at,cutover_epoch,
                    updated_by,updated_at,lock_version)
                VALUES(1,'VERIFY_FENCE',?,?,?,NOW(6),0,'test-setup',NOW(6),0)
                """, buildIdentity.schemaGeneration(), buildIdentity.binaryGeneration(),
                buildIdentity.buildDigest());
        StageBMigrationProperties properties = new StageBMigrationProperties();
        properties.setAction(StageBMigrationAction.VERIFY);
        properties.setBatchSize(100);
        properties.setVerificationPageSize(20);
        properties.setElasticsearchReadAlias("article-read");
        properties.setOperatorIdentity("revision-aware-integration");
        properties.setVerificationReportPath(
                temporaryDirectory.resolve(artifactName).toAbsolutePath().toString());
        ArticleRevisionModeResolver fence = () -> ArticleRevisionMode.VERIFY_FENCE;
        StageBArticleMigrationVerifier verifier = new DefaultStageBArticleMigrationVerifier(
                jdbcTemplate, elasticsearchClient, elasticsearchRestClient, canonicalizer,
                objectMapper, fence, properties, fingerprintService);
        StageBMigrationRunner runner = new StageBMigrationRunner(
                properties, fence, migrationService, verifier, rolloutOperator,
                buildIdentity, new StageBVerificationArtifactWriter(properties, objectMapper));
        return new PreparedVerify(runner, properties);
    }

    private StageBMigrationReport readArtifact(StageBMigrationProperties properties) {
        try {
            return objectMapper.readValue(
                    Path.of(properties.getVerificationReportPath()).toFile(),
                    StageBVerificationArtifact.class).report();
        }
        catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private NativeSubmission submitNative(String title, String body, List<String> tags) {
        ArticleDraft draft = mutationFacade.saveDraft(new SaveArticleDraftCommand(
                null, 0, title, "summary", body, "cover", tags), AUTHOR_ID);
        SubmissionResult submission = mutationFacade.submit(new SubmitArticleRevisionCommand(
                draft.getArticleId(), AUTHOR_ID, draft.getDraftVersion()));
        return new NativeSubmission(draft, submission);
    }

    private void decide(SubmissionResult submission, boolean approve, String reason) {
        ModerationJobResponse pending = decisionService.get(submission.moderationJobId());
        ModerationDecisionRequest request = new ModerationDecisionRequest(
                pending.revisionId(), pending.jobVersion(), pending.articleVersion(), reason);
        if (approve) {
            decisionService.approve(pending.id(), request, AUTHOR_ID);
        }
        else {
            decisionService.reject(pending.id(), request, AUTHOR_ID);
        }
    }

    private void installModelEvidence(long jobId, int attemptCount) {
        jdbcTemplate.update("""
                UPDATE article_moderation_job
                SET state='HUMAN_PENDING',model_decision='PASS',risk_score=0.1250,
                    policy_hits_json=JSON_OBJECT('categories',JSON_ARRAY('SAFE'),
                                                 'severity',0,'confidence',0.99,
                                                 'uncertain',FALSE),
                    attempt_count=?,last_error=NULL,lease_owner=NULL,lease_until=NULL,
                    updated_at=NOW(6),lock_version=2
                WHERE id=?
                """, attemptCount, jobId);
    }

    private void insertSuccessfulAttempt(NativeSubmission submission, int attemptNo,
                                         String inputHash) {
        jdbcTemplate.update("""
                INSERT INTO article_moderation_attempt(
                    job_id,attempt_no,provider,model,prompt_version,input_hash,
                    structured_output_json,latency_ms,token_usage_json,finish_reason,
                    error_code,created_at)
                VALUES(?,?,'deepseek','moderation-test','article-moderation-v1',?,
                       JSON_OBJECT('chunk',JSON_OBJECT('index',0,'sourceStart',0,'sourceEnd',4,
                                      'estimatedInputTokens',2),
                                   'modelOutput',JSON_OBJECT('decision','PASS')),
                       17,JSON_OBJECT('totalTokens',11),'stop',NULL,NOW(6))
                """, submission.submission().moderationJobId(), attemptNo, inputHash);
    }

    private Map<String, Object> attemptState(long jobId) {
        return jdbcTemplate.queryForMap("""
                SELECT id,job_id,attempt_no,provider,model,prompt_version,input_hash,
                       structured_output_json,latency_ms,token_usage_json,finish_reason,
                       error_code,created_at
                FROM article_moderation_attempt WHERE job_id=?
                """, jobId);
    }

    private void saveMatchingDocument(long articleId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT a.id,a.author_id,a.view_count,a.like_count,a.comment_count,a.collect_count,
                       a.create_time,r.id AS revision_id,r.content_hash,
                       r.title,r.body_markdown,r.summary,r.cover
                FROM article a
                JOIN article_revision r ON r.id=a.published_revision_id AND r.article_id=a.id
                WHERE a.id=?
                """, articleId);
        ArticleDoc document = new ArticleDoc();
        document.setId(((Number) row.get("id")).longValue());
        document.setRevisionId(((Number) row.get("revision_id")).longValue());
        document.setContentHash((String) row.get("content_hash"));
        document.setAuthorId(((Number) row.get("author_id")).longValue());
        document.setViewCount(((Number) row.get("view_count")).intValue());
        document.setLikeCount(((Number) row.get("like_count")).intValue());
        document.setCommentCount(((Number) row.get("comment_count")).intValue());
        document.setCollectCount(((Number) row.get("collect_count")).intValue());
        Object createTime = row.get("create_time");
        document.setCreateTime(createTime instanceof Timestamp timestamp
                ? timestamp.toLocalDateTime() : (LocalDateTime) createTime);
        document.setTitle((String) row.get("title"));
        document.setContent((String) row.get("body_markdown"));
        document.setSummary((String) row.get("summary"));
        document.setCover((String) row.get("cover"));
        articleRepository.save(document);
    }

    private Map<String, Object> articleState(long articleId) {
        return jdbcTemplate.queryForMap("""
                SELECT title,summary,content,cover,status,is_deleted,
                       latest_revision_id,pending_revision_id,published_revision_id,
                       visibility_state,review_state,lifecycle_epoch,lock_version,update_time
                FROM article WHERE id=?
                """, articleId);
    }

    private Map<String, Object> draftState(long articleId) {
        return jdbcTemplate.queryForMap("""
                SELECT article_id,user_id,draft_version,title,summary,body_markdown,body_plain,
                       cover,tags_json,content_hash,created_at,updated_at,lock_version
                FROM article_draft WHERE article_id=?
                """, articleId);
    }

    private Map<String, Object> revisionState(long revisionId) {
        return jdbcTemplate.queryForMap("""
                SELECT id,article_id,revision_no,title,summary,body_markdown,body_plain,cover,
                       tags_json,content_hash,source_draft_version,created_by,created_at
                FROM article_revision WHERE id=?
                """, revisionId);
    }

    private Map<String, Object> jobState(long jobId) {
        return jdbcTemplate.queryForMap("""
                SELECT id,article_id,revision_id,content_hash,state,model_decision,risk_score,
                       policy_hits_json,attempt_count,next_attempt_at,lease_owner,lease_until,
                       last_error,reviewer_id,review_reason,reviewed_at,created_at,updated_at,lock_version
                FROM article_moderation_job WHERE id=?
                """, jobId);
    }

    private void ensureArticleReadAlias() {
        try {
            elasticsearchClient.indices().putAlias(request ->
                    request.index("article").name("article-read"));
        }
        catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void refreshArticleIndex() {
        try {
            elasticsearchClient.indices().refresh(request -> request.index("article"));
        }
        catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record NativeSubmission(ArticleDraft draft, SubmissionResult submission) {
    }

    private record PreparedVerify(StageBMigrationRunner runner,
                                  StageBMigrationProperties properties) {
    }
}
