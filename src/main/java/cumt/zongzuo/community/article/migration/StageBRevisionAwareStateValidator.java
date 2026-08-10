package cumt.zongzuo.community.article.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.article.model.ArticleContentSnapshot;
import cumt.zongzuo.community.article.service.ArticleContentCanonicalizer;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared integrity contract for rows already owned by the revision model. The
 * operator backfill and the promotion verifier must classify and validate the
 * same durable tuple; neither is allowed to reinterpret a live row as legacy.
 */
final class StageBRevisionAwareStateValidator {

    private static final String LEGACY_BACKFILL_MANUAL = "LEGACY_BACKFILL_MANUAL";
    private static final String LEGACY_SHADOW_MANUAL = "LEGACY_SHADOW_MANUAL";
    private static final Set<String> FINAL_JOB_STATES = Set.of(
            "PENDING", "HUMAN_PENDING", "HUMAN_APPROVED", "HUMAN_REJECTED", "SUPERSEDED");
    private static final Set<String> MODEL_DECISIONS = Set.of("PASS", "REVIEW", "REJECT");

    private final ArticleContentCanonicalizer canonicalizer;
    private final ObjectMapper objectMapper;

    StageBRevisionAwareStateValidator(ArticleContentCanonicalizer canonicalizer,
                                      ObjectMapper objectMapper) {
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    Validation validate(ArticleState article, DraftState draft,
                        List<RevisionState> revisions, List<JobState> jobs,
                        List<AttemptState> attempts,
                        ArticleContentSnapshot legacy) {
        boolean visibilityDeclared = article.visibilityState() != null;
        boolean reviewDeclared = article.reviewState() != null;
        if (!visibilityDeclared && !reviewDeclared) {
            return Validation.legacy();
        }

        List<Violation> violations = new ArrayList<>();
        if (visibilityDeclared != reviewDeclared) {
            violations.add(new Violation("REVISION_STATE_DECLARATION_MISMATCH",
                    "visibility_state and review_state must be declared together"));
        }
        ArticleContentSnapshot draftSnapshot = validateDraft(article, draft, violations);
        Map<Long, RevisionState> revisionsById = validateRevisions(article, revisions, violations);
        if (isNativeDraftOnly(article, revisions, jobs)) {
            if (draftSnapshot == null) {
                return new Validation(Classification.REVISION_AWARE, List.copyOf(violations));
            }
            return new Validation(Classification.NEEDS_BASELINE, List.copyOf(violations));
        }
        if (revisions.stream().noneMatch(revision -> revision.revisionNo() == 1)) {
            violations.add(new Violation("BASELINE_REVISION_MISSING", "revision 1 is missing"));
        }
        validatePointers(article, revisionsById, violations);
        validateJobs(article, revisionsById, jobs, attempts, violations);
        validateArticleTuple(article, revisionsById, jobs, violations);

        if (article.publishedRevisionId() != null) {
            RevisionState published = revisionsById.get(article.publishedRevisionId());
            ArticleContentSnapshot snapshot = snapshot(published);
            if (snapshot == null || !snapshot.contentHash().equals(legacy.contentHash())) {
                violations.add(new Violation("PUBLISHED_REVISION_MISMATCH",
                        "published revision differs from the public legacy mirror"));
            }
        }
        return new Validation(Classification.REVISION_AWARE, List.copyOf(violations));
    }

    private ArticleContentSnapshot validateDraft(ArticleState article, DraftState draft,
                                                 List<Violation> violations) {
        if (draft == null) {
            violations.add(new Violation("DRAFT_MISSING", "article has no current draft"));
            return null;
        }
        ArticleContentSnapshot snapshot = snapshot(draft.title(), draft.summary(),
                draft.bodyMarkdown(), draft.cover(), draft.tagsJson());
        if (draft.articleId() != article.id() || draft.userId() != article.authorId()
                || snapshot == null || !snapshot.contentHash().equals(draft.contentHash())
                || !Objects.equals(snapshot.bodyPlain(), draft.bodyPlain())) {
            violations.add(new Violation("DRAFT_HASH_MISMATCH",
                    "draft ownership or canonical self-hash is invalid"));
            return null;
        }
        return snapshot;
    }

    private Map<Long, RevisionState> validateRevisions(ArticleState article,
                                                       List<RevisionState> revisions,
                                                       List<Violation> violations) {
        LinkedHashMap<Long, RevisionState> byId = new LinkedHashMap<>();
        for (RevisionState revision : revisions) {
            if (byId.putIfAbsent(revision.id(), revision) != null) {
                violations.add(new Violation("REVISION_ID_DUPLICATE", "duplicate revision id"));
                continue;
            }
            ArticleContentSnapshot snapshot = snapshot(revision);
            if (revision.articleId() != article.id() || revision.createdBy() != article.authorId()) {
                violations.add(new Violation("REVISION_OWNERSHIP_MISMATCH",
                        "revision belongs to another article or author"));
            }
            if (snapshot == null || !snapshot.contentHash().equals(revision.contentHash())
                    || !Objects.equals(snapshot.bodyPlain(), revision.bodyPlain())) {
                violations.add(new Violation("REVISION_SELF_HASH_MISMATCH",
                        "revisionId=" + revision.id()));
            }
        }
        return byId;
    }

    private void validatePointers(ArticleState article, Map<Long, RevisionState> revisions,
                                  List<Violation> violations) {
        for (Long pointer : new Long[]{article.latestRevisionId(), article.pendingRevisionId(),
                article.publishedRevisionId()}) {
            if (pointer == null) {
                continue;
            }
            if (!revisions.containsKey(pointer)) {
                violations.add(new Violation("ARTICLE_POINTER_MISMATCH",
                        "article pointer does not name an owned immutable revision"));
            }
        }
    }

    private void validateJobs(ArticleState article, Map<Long, RevisionState> revisions,
                              List<JobState> jobs, List<AttemptState> attempts,
                              List<Violation> violations) {
        Map<Long, JobState> jobsById = new LinkedHashMap<>();
        for (JobState job : jobs) {
            if (jobsById.putIfAbsent(job.id(), job) != null) {
                violations.add(new Violation("MODERATION_JOB_ID_DUPLICATE",
                        "duplicate moderation job id"));
            }
        }
        Map<Long, List<AttemptState>> attemptsByJob = validateAttempts(
                jobsById, attempts, violations);
        for (JobState job : jobs) {
            RevisionState revision = revisions.get(job.revisionId());
            if (job.articleId() != article.id() || revision == null) {
                violations.add(new Violation("MODERATION_JOB_BINDING_MISMATCH",
                        "moderation job is not bound to an owned revision"));
                continue;
            }
            if (!Objects.equals(job.contentHash(), revision.contentHash())) {
                violations.add(new Violation("MODERATION_JOB_HASH_MISMATCH",
                        "moderation job hash differs from its revision"));
            }
            if (!FINAL_JOB_STATES.contains(job.state())) {
                violations.add(new Violation("MODERATION_JOB_STATE_MISMATCH",
                        "moderation job is not in a drained Task 7 state"));
            }
            if (job.leaseOwner() != null || job.leaseUntil() != null) {
                violations.add(new Violation("MODERATION_JOB_LEASE_MISMATCH",
                        "drained moderation job retains a lease"));
            }
            validateEvidence(job, attemptsByJob.getOrDefault(job.id(), List.of()), violations);
            validateReviewFact(job, violations);
            if (("PENDING".equals(job.state()) || "HUMAN_PENDING".equals(job.state()))
                    && !Objects.equals(article.pendingRevisionId(), job.revisionId())) {
                violations.add(new Violation("MODERATION_JOB_ORPHAN_NONTERMINAL",
                        "nonterminal job is not the article pending revision"));
            }
        }
    }

    private Map<Long, List<AttemptState>> validateAttempts(Map<Long, JobState> jobs,
                                                           List<AttemptState> attempts,
                                                           List<Violation> violations) {
        Map<Long, List<AttemptState>> byJob = new LinkedHashMap<>();
        Map<Long, Set<Integer>> attemptNumbers = new LinkedHashMap<>();
        Set<Long> attemptIds = new HashSet<>();
        for (AttemptState attempt : attempts) {
            JobState job = jobs.get(attempt.jobId());
            if (!attemptIds.add(attempt.id())) {
                violations.add(new Violation("MODERATION_ATTEMPT_ID_DUPLICATE",
                        "duplicate moderation attempt id"));
            }
            if (job == null) {
                violations.add(new Violation("MODERATION_ATTEMPT_BINDING_MISMATCH",
                        "moderation attempt is not bound to an article job"));
                continue;
            }
            byJob.computeIfAbsent(job.id(), ignored -> new ArrayList<>()).add(attempt);
            boolean uniqueNumber = attemptNumbers
                    .computeIfAbsent(job.id(), ignored -> new HashSet<>())
                    .add(attempt.attemptNo());
            if (attempt.attemptNo() < 1 || attempt.attemptNo() > job.attemptCount()
                    || !uniqueNumber) {
                violations.add(new Violation("MODERATION_ATTEMPT_SEQUENCE_MISMATCH",
                        "attempt_no must be unique, positive and no greater than attempt_count"));
            }
            validateAttemptIntegrity(attempt, violations);
        }
        return byJob;
    }

    private void validateAttemptIntegrity(AttemptState attempt, List<Violation> violations) {
        boolean structured = validObject(attempt.structuredOutputJson(), "chunk");
        boolean successfulEvidence = attempt.errorCode() == null
                && validObject(attempt.structuredOutputJson(), "modelOutput");
        boolean validTokenUsage = attempt.tokenUsageJson() == null
                || validObject(attempt.tokenUsageJson(), null);
        boolean valid = attempt.id() > 0 && attempt.jobId() > 0
                && hasText(attempt.provider()) && hasText(attempt.model())
                && hasText(attempt.promptVersion())
                && attempt.inputHash() != null
                && attempt.inputHash().matches("[0-9a-f]{64}")
                && structured && attempt.latencyMs() >= 0 && validTokenUsage
                && (attempt.finishReason() == null || hasText(attempt.finishReason()))
                && (attempt.errorCode() == null || hasText(attempt.errorCode()))
                && attempt.createdAt() != null
                && (attempt.errorCode() != null || successfulEvidence);
        if (!valid) {
            violations.add(new Violation("MODERATION_ATTEMPT_INTEGRITY_MISMATCH",
                    "moderation attempt audit fields are incomplete or invalid"));
        }
    }

    private void validateEvidence(JobState job, List<AttemptState> attempts,
                                  List<Violation> violations) {
        boolean hasAnyModelField = job.modelDecision() != null || job.riskScore() != null
                || job.policyHitsJson() != null;
        if (job.modelDecision() == null && hasAnyModelField) {
            violations.add(new Violation("MODERATION_JOB_MODEL_EVIDENCE_MISMATCH",
                    "partial model evidence is not valid"));
        }
        if (job.modelDecision() != null) {
            boolean validJson = false;
            try {
                validJson = job.policyHitsJson() != null
                        && objectMapper.readTree(job.policyHitsJson()).isObject();
            }
            catch (Exception ignored) {
                // Report one stable mismatch below.
            }
            if (!MODEL_DECISIONS.contains(job.modelDecision()) || job.riskScore() == null
                    || !validJson || job.attemptCount() < 1) {
                violations.add(new Violation("MODERATION_JOB_MODEL_EVIDENCE_MISMATCH",
                        "model evidence is incomplete or invalid"));
            }
            boolean successfulAttempt = attempts.stream()
                    .anyMatch(attempt -> validSuccessfulAttempt(job, attempt));
            if (!successfulAttempt) {
                violations.add(new Violation("MODERATION_JOB_ATTEMPT_EVIDENCE_MISMATCH",
                        "model evidence has no valid successful provider attempt"));
            }
        }
        if ("PENDING".equals(job.state()) && (hasAnyModelField || job.attemptCount() != 0
                || job.nextAttemptAt() != null || job.lastError() != null)) {
            violations.add(new Violation("MODERATION_JOB_FROZEN_FIELDS_MISMATCH",
                    "fresh pending job contains provider or retry state"));
        }
        if (job.attemptCount() < 0 || job.lockVersion() < 0) {
            violations.add(new Violation("MODERATION_JOB_FROZEN_FIELDS_MISMATCH",
                    "moderation counters are invalid"));
        }
    }

    private boolean validSuccessfulAttempt(JobState job, AttemptState attempt) {
        return attempt.attemptNo() > 0 && attempt.attemptNo() <= job.attemptCount()
                && attempt.errorCode() == null
                && attempt.inputHash() != null
                && attempt.inputHash().matches("[0-9a-f]{64}")
                && hasText(attempt.provider()) && hasText(attempt.model())
                && hasText(attempt.promptVersion()) && attempt.latencyMs() >= 0
                && attempt.createdAt() != null
                && validObject(attempt.structuredOutputJson(), "chunk")
                && validObject(attempt.structuredOutputJson(), "modelOutput");
    }

    private boolean validObject(String json, String requiredChild) {
        try {
            var node = json == null ? null : objectMapper.readTree(json);
            return node != null && node.isObject()
                    && (requiredChild == null || node.path(requiredChild).isObject());
        }
        catch (Exception invalid) {
            return false;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void validateReviewFact(JobState job, List<Violation> violations) {
        boolean anyReview = job.reviewerId() != null || job.reviewReason() != null
                || job.reviewedAt() != null;
        boolean finalHuman = "HUMAN_APPROVED".equals(job.state())
                || "HUMAN_REJECTED".equals(job.state());
        if (finalHuman) {
            if (job.reviewerId() == null || job.reviewReason() == null
                    || job.reviewReason().isBlank() || job.reviewedAt() == null) {
                violations.add(new Violation("MODERATION_JOB_REVIEW_FACT_MISMATCH",
                        "human decision is missing its immutable review fact"));
            }
        }
        else if (anyReview) {
            violations.add(new Violation("MODERATION_JOB_REVIEW_FACT_MISMATCH",
                    "non-decision job contains review fields"));
        }
    }

    private void validateArticleTuple(ArticleState article, Map<Long, RevisionState> revisions,
                                      List<JobState> jobs, List<Violation> violations) {
        if (article.isDeleted() == 0) {
            validateActiveTuple(article, revisions, jobs, violations);
            return;
        }
        if (article.isDeleted() != 1) {
            violations.add(new Violation("INVALID_LEGACY_STATE", "delete flag is invalid"));
            return;
        }
        if (!"RECYCLED".equals(article.visibilityState())
                && !"PURGED".equals(article.visibilityState())) {
            violations.add(new Violation("ARTICLE_VISIBILITY_MISMATCH",
                    "deleted revision-aware row must be RECYCLED or PURGED"));
        }
        if ("PURGED".equals(article.visibilityState())) {
            validateNormalizedDeletedTuple(article, jobs, violations);
            return;
        }
        if (article.pendingRevisionId() != null) {
            if (article.status() != 2 || article.publishedRevisionId() != null
                    || !Objects.equals(article.latestRevisionId(), article.pendingRevisionId())
                    || !"AUTO_PENDING".equals(article.reviewState())) {
                violations.add(new Violation("ARTICLE_DELETED_POINTER_MISMATCH",
                        "only the exact legacy recycled-pending tuple may retain pending"));
            }
            validateCurrentPending(article, jobs, violations);
            return;
        }
        if (article.status() == 3 && article.publishedRevisionId() == null
                && article.latestRevisionId() != null
                && "REJECTED".equals(article.reviewState())) {
            ensureNoNonterminal(jobs, violations);
            return;
        }
        validateNormalizedDeletedTuple(article, jobs, violations);
    }

    private void validateNormalizedDeletedTuple(ArticleState article, List<JobState> jobs,
                                                List<Violation> violations) {
        boolean published = article.publishedRevisionId() != null;
        boolean valid = article.pendingRevisionId() == null
                && article.status() == (published ? 1 : 0)
                && Objects.equals(article.latestRevisionId(), article.publishedRevisionId())
                && (published ? "APPROVED" : "NOT_SUBMITTED").equals(article.reviewState());
        if (!valid) {
            violations.add(new Violation("ARTICLE_DELETED_POINTER_MISMATCH",
                    "deleted tuple is not normalized to its approved published pointer"));
        }
        if (published) {
            validatePublishedDecision(article, jobs, violations);
        }
        ensureNoNonterminal(jobs, violations);
    }

    private void validateActiveTuple(ArticleState article,
                                     Map<Long, RevisionState> revisions,
                                     List<JobState> jobs,
                                     List<Violation> violations) {
        String expectedVisibility = article.publishedRevisionId() == null ? "PRIVATE" : "PUBLIC";
        if (!expectedVisibility.equals(article.visibilityState())) {
            violations.add(new Violation("ARTICLE_VISIBILITY_MISMATCH",
                    "visibility does not match the published pointer"));
        }
        if (article.pendingRevisionId() != null) {
            int expectedStatus = article.publishedRevisionId() == null ? 2 : 1;
            if (article.status() != expectedStatus
                    || !Objects.equals(article.latestRevisionId(), article.pendingRevisionId())
                    || !("PENDING".equals(article.reviewState())
                         || "HUMAN_PENDING".equals(article.reviewState())
                         || "AUTO_PENDING".equals(article.reviewState()))) {
                violations.add(new Violation("PENDING_POINTER_MISMATCH",
                        "active pending tuple is inconsistent"));
            }
            validateCurrentPending(article, jobs, violations);
            return;
        }
        if (article.publishedRevisionId() != null) {
            boolean latestIsPublished = Objects.equals(
                    article.latestRevisionId(), article.publishedRevisionId());
            boolean latestIsRejectedReplacement = rejectedReplacementIsLatest(
                    article, revisions, jobs);
            if (article.status() != 1
                    || (!latestIsPublished && !latestIsRejectedReplacement)
                    || !"APPROVED".equals(article.reviewState())) {
                violations.add(new Violation("PUBLISHED_POINTER_MISMATCH",
                        "approved tuple requires latest=published or an exact rejected replacement"));
            }
            validatePublishedDecision(article, jobs, violations);
            ensureNoNonterminal(jobs, violations);
            return;
        }
        boolean draft = article.status() == 0 && article.latestRevisionId() == null
                && "NOT_SUBMITTED".equals(article.reviewState());
        boolean rejected = article.status() == 3 && article.latestRevisionId() != null
                && "REJECTED".equals(article.reviewState());
        if (!draft && !rejected) {
            violations.add(new Violation("ARTICLE_POINTER_MISMATCH",
                    "private tuple is neither draft nor rejected"));
        }
        ensureNoNonterminal(jobs, violations);
    }

    private void validatePublishedDecision(ArticleState article, List<JobState> jobs,
                                           List<Violation> violations) {
        List<JobState> bound = jobs.stream()
                .filter(job -> job.articleId() == article.id())
                .filter(job -> Objects.equals(job.revisionId(), article.publishedRevisionId()))
                .toList();
        if (bound.isEmpty()) {
            // A migrated legacy approved baseline has no human-decision job.
            return;
        }
        if (bound.size() != 1 || !"HUMAN_APPROVED".equals(bound.getFirst().state())) {
            violations.add(new Violation("MODERATION_DECISION_TUPLE_MISMATCH",
                    "published revision contradicts its immutable human approval"));
        }
    }

    private boolean rejectedReplacementIsLatest(ArticleState article,
                                                 Map<Long, RevisionState> revisions,
                                                 List<JobState> jobs) {
        if (article.latestRevisionId() == null || article.publishedRevisionId() == null
                || Objects.equals(article.latestRevisionId(), article.publishedRevisionId())) {
            return false;
        }
        RevisionState latest = revisions.get(article.latestRevisionId());
        RevisionState published = revisions.get(article.publishedRevisionId());
        if (latest == null || published == null || latest.revisionNo() <= published.revisionNo()) {
            return false;
        }
        long maximumRevision = revisions.values().stream()
                .mapToLong(RevisionState::revisionNo).max().orElse(0);
        if (latest.revisionNo() != maximumRevision) {
            return false;
        }
        List<JobState> decisions = jobs.stream()
                .filter(job -> job.revisionId() == latest.id())
                .filter(job -> job.articleId() == article.id())
                .filter(job -> "HUMAN_REJECTED".equals(job.state()))
                .filter(job -> Objects.equals(job.contentHash(), latest.contentHash()))
                .toList();
        return decisions.size() == 1;
    }

    private void validateCurrentPending(ArticleState article, List<JobState> jobs,
                                        List<Violation> violations) {
        List<JobState> current = jobs.stream()
                .filter(job -> Objects.equals(article.pendingRevisionId(), job.revisionId()))
                .toList();
        if (current.size() != 1) {
            violations.add(new Violation("MODERATION_JOB_COUNT_MISMATCH",
                    "pending revision must have exactly one bound job"));
            return;
        }
        JobState job = current.getFirst();
        if (!"PENDING".equals(job.state()) && !"HUMAN_PENDING".equals(job.state())) {
            violations.add(new Violation("MODERATION_JOB_STATE_MISMATCH",
                    "pending revision job is not actionable"));
        }
        if ("AUTO_PENDING".equals(article.reviewState())) {
            if (!"HUMAN_PENDING".equals(job.state())) {
                violations.add(new Violation("MODERATION_JOB_STATE_MISMATCH",
                        "legacy pending job is not HUMAN_PENDING"));
            }
            if (!isLegacyReason(job.lastError())) {
                violations.add(new Violation("MODERATION_JOB_REASON_MISMATCH",
                        "legacy pending reason is not a migration marker"));
            }
            if (!job.hasUntouchedLegacyFields()) {
                violations.add(new Violation("MODERATION_JOB_FROZEN_FIELDS_MISMATCH",
                        "legacy pending job contains provider, lease, review or version state"));
            }
        }
        else if ("HUMAN_PENDING".equals(article.reviewState())
                && !"HUMAN_PENDING".equals(job.state())) {
            violations.add(new Violation("MODERATION_JOB_STATE_MISMATCH",
                    "article HUMAN_PENDING does not have a human-pending job"));
        }
    }

    private void ensureNoNonterminal(List<JobState> jobs, List<Violation> violations) {
        if (jobs.stream().anyMatch(job -> "PENDING".equals(job.state())
                || "HUMAN_PENDING".equals(job.state()))) {
            violations.add(new Violation("MODERATION_JOB_ORPHAN_NONTERMINAL",
                    "article without a pending pointer retains a nonterminal job"));
        }
    }

    private boolean isNativeDraftOnly(ArticleState article, List<RevisionState> revisions,
                                      List<JobState> jobs) {
        return article.isDeleted() == 0 && article.status() == 0
                && article.latestRevisionId() == null && article.pendingRevisionId() == null
                && article.publishedRevisionId() == null && "PRIVATE".equals(article.visibilityState())
                && "NOT_SUBMITTED".equals(article.reviewState())
                && revisions.isEmpty() && jobs.isEmpty();
    }

    private boolean isLegacyReason(String reason) {
        return LEGACY_BACKFILL_MANUAL.equals(reason) || LEGACY_SHADOW_MANUAL.equals(reason);
    }

    private ArticleContentSnapshot snapshot(RevisionState revision) {
        return revision == null ? null : snapshot(revision.title(), revision.summary(),
                revision.bodyMarkdown(), revision.cover(), revision.tagsJson());
    }

    private ArticleContentSnapshot snapshot(String title, String summary, String body,
                                            String cover, String tagsJson) {
        try {
            List<String> tags = objectMapper.readerForListOf(String.class).readValue(tagsJson);
            return canonicalizer.canonicalize(title, summary, body, cover, tags);
        }
        catch (Exception invalid) {
            return null;
        }
    }

    enum Classification {
        LEGACY,
        NEEDS_BASELINE,
        REVISION_AWARE
    }

    record Validation(Classification classification, List<Violation> violations) {
        private static Validation legacy() {
            return new Validation(Classification.LEGACY, List.of());
        }

        boolean valid() {
            return violations.isEmpty();
        }
    }

    record Violation(String code, String detail) {
    }

    record ArticleState(long id, long authorId, int status, int isDeleted,
                        Long latestRevisionId, Long pendingRevisionId, Long publishedRevisionId,
                        String visibilityState, String reviewState) {
    }

    record DraftState(long articleId, long userId, String title, String summary,
                      String bodyMarkdown, String bodyPlain, String cover, String tagsJson,
                      String contentHash) {
    }

    record RevisionState(long id, long articleId, long revisionNo, String title, String summary,
                         String bodyMarkdown, String bodyPlain, String cover, String tagsJson,
                         String contentHash, long createdBy) {
    }

    record JobState(long id, long articleId, long revisionId, String contentHash, String state,
                    String modelDecision, String riskScore, String policyHitsJson, int attemptCount,
                    Timestamp nextAttemptAt, String leaseOwner, Timestamp leaseUntil,
                    String lastError, Long reviewerId, String reviewReason, Timestamp reviewedAt,
                    long lockVersion) {

        boolean hasUntouchedLegacyFields() {
            return modelDecision == null && riskScore == null && policyHitsJson == null
                    && attemptCount == 0 && nextAttemptAt == null && leaseOwner == null
                    && leaseUntil == null && reviewerId == null && reviewReason == null
                    && reviewedAt == null && lockVersion == 0;
        }
    }

    record AttemptState(long id, long jobId, int attemptNo, String provider, String model,
                        String promptVersion, String inputHash, String structuredOutputJson,
                        long latencyMs, String tokenUsageJson, String finishReason,
                        String errorCode, Timestamp createdAt) {
    }
}
