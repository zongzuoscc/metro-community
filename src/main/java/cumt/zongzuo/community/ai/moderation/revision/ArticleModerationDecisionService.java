package cumt.zongzuo.community.ai.moderation.revision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.moderation.web.ModerationJobPageResponse;
import cumt.zongzuo.community.ai.moderation.web.ModerationJobResponse;
import cumt.zongzuo.community.ai.moderation.web.ModerationRevisionResponse;
import cumt.zongzuo.community.ai.moderation.web.ModerationDecisionRequest;
import cumt.zongzuo.community.ai.web.AiApiException;
import cumt.zongzuo.community.article.model.ArticleRevision;
import cumt.zongzuo.community.article.persistence.ArticleRevisionMapper;
import cumt.zongzuo.community.article.service.ArticleRevisionIntegrityVerifier;
import cumt.zongzuo.community.article.service.ArticleMutationGate;
import cumt.zongzuo.community.article.service.PublishedArticleMirrorWriter;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.mapper.ArticleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.PessimisticLockingFailureException;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.outbox.DomainEventOutboxService;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;

import java.util.List;
import java.util.Set;

@Service
public class ArticleModerationDecisionService {

    private static final Set<String> STATES = Set.of(
            "PENDING", "RUNNING", "RETRY_WAIT", "MODEL_PASS", "MODEL_REVIEW",
            "MODEL_REJECT", "HUMAN_PENDING", "HUMAN_APPROVED", "HUMAN_REJECTED",
            "SUPERSEDED");

    private final ArticleModerationJobMapper jobMapper;
    private final ArticleMapper articleMapper;
    private final ArticleRevisionMapper revisionMapper;
    private final ArticleRevisionIntegrityVerifier integrityVerifier;
    private final ObjectMapper objectMapper;
    private final ArticleMutationGate mutationGate;
    private final PublishedArticleMirrorWriter mirrorWriter;
    private final DomainEventOutboxService outboxService;
    private final TransactionTemplate decisionTransactions;

    public ArticleModerationDecisionService(ArticleModerationJobMapper jobMapper,
                                            ArticleMapper articleMapper,
                                            ArticleRevisionMapper revisionMapper,
                                            ArticleRevisionIntegrityVerifier integrityVerifier,
                                            ObjectMapper objectMapper,
                                            ArticleMutationGate mutationGate,
                                            PublishedArticleMirrorWriter mirrorWriter,
                                            DomainEventOutboxService outboxService,
                                            PlatformTransactionManager transactionManager) {
        this.jobMapper = jobMapper;
        this.articleMapper = articleMapper;
        this.revisionMapper = revisionMapper;
        this.integrityVerifier = integrityVerifier;
        this.objectMapper = objectMapper;
        this.mutationGate = mutationGate;
        this.mirrorWriter = mirrorWriter;
        this.outboxService = outboxService;
        this.decisionTransactions = new TransactionTemplate(transactionManager);
    }

    public ModerationJobResponse get(long jobId) {
        ArticleModerationJob job = jobMapper.selectById(jobId);
        if (job == null) {
            throw AiApiException.resourceNotFound();
        }
        return response(job);
    }

    public ModerationJobPageResponse list(String state, Long before, int size) {
        String normalizedState = state == null || state.isBlank() ? null : state.trim().toUpperCase();
        if (normalizedState != null && !STATES.contains(normalizedState)) {
            throw AiApiException.validationFailed();
        }
        List<ArticleModerationJob> rows = jobMapper.selectAdminPage(
                normalizedState, before, size + 1);
        boolean hasMore = rows.size() > size;
        List<ModerationJobResponse> items = rows.stream().limit(size).map(this::response).toList();
        Long nextBefore = hasMore && !items.isEmpty() ? items.getLast().id() : null;
        return new ModerationJobPageResponse(items, nextBefore, hasMore);
    }

    public ModerationJobResponse approve(long jobId, ModerationDecisionRequest request,
                                         long reviewerId) {
        return decideWithDeadlockRetry(jobId, request, reviewerId, true);
    }

    public ModerationJobResponse reject(long jobId, ModerationDecisionRequest request,
                                        long reviewerId) {
        return decideWithDeadlockRetry(jobId, request, reviewerId, false);
    }

    private ModerationJobResponse decideWithDeadlockRetry(
            long jobId, ModerationDecisionRequest request, long reviewerId, boolean approve) {
        PessimisticLockingFailureException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                ModerationJobResponse response = decisionTransactions.execute(
                        status -> decide(jobId, request, reviewerId, approve));
                if (response == null) {
                    throw new IllegalStateException("moderation decision transaction returned null");
                }
                return response;
            }
            catch (PessimisticLockingFailureException retryable) {
                lastFailure = retryable;
            }
        }
        throw lastFailure;
    }

    private ModerationJobResponse decide(long jobId, ModerationDecisionRequest request,
                                         long reviewerId, boolean approve) {
        mutationGate.requireRevisionModerationDecisionAllowed();
        ArticleModerationJob hint = jobMapper.selectById(jobId);
        if (hint == null) {
            throw AiApiException.resourceNotFound();
        }

        // All article writers use the same global lock order: article -> job -> immutable revision.
        Article article = articleMapper.selectByIdForUpdate(hint.getArticleId());
        ArticleModerationJob job = jobMapper.selectByIdForUpdate(jobId);
        ArticleRevision revision = job == null ? null : revisionMapper.selectByIdForUpdate(job.getRevisionId());
        validateDecisionTuple(hint, job, article, revision, request);

        LocalDateTime decidedAt = LocalDateTime.now();
        String targetState = approve ? "HUMAN_APPROVED" : "HUMAN_REJECTED";
        if (jobMapper.decideHumanPendingExact(jobId, article.getId(), revision.getId(),
                revision.getContentHash(), request.expectedJobVersion(), targetState,
                reviewerId, request.reason().trim(), decidedAt) != 1) {
            throw AiApiException.optimisticLockConflict();
        }

        Long oldPublishedRevisionId = article.getPublishedRevisionId();
        if (approve) {
            mirrorWriter.publishLocked(article.getId(), revision,
                    request.expectedArticleVersion(), decidedAt);
        }
        else {
            boolean remainsPublic = oldPublishedRevisionId != null;
            if (articleMapper.rejectRevisionCas(article.getId(), revision.getId(),
                    remainsPublic ? "PUBLIC" : "PRIVATE",
                    remainsPublic ? "APPROVED" : "REJECTED", remainsPublic ? 1 : 3,
                    request.expectedArticleVersion(), decidedAt) != 1) {
                throw AiApiException.optimisticLockConflict();
            }
        }

        long aggregateVersion = request.expectedArticleVersion() + 1;
        DomainEventType eventType = approve
                ? DomainEventType.ARTICLE_REVISION_PUBLISHED
                : DomainEventType.ARTICLE_REVISION_REJECTED;
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("articleId", article.getId());
        payload.put("revisionId", revision.getId());
        payload.put("moderationJobId", job.getId());
        payload.put("contentHash", revision.getContentHash());
        putNullable(payload, "oldPublishedRevisionId", oldPublishedRevisionId);
        putNullable(payload, "newPublishedRevisionId", approve ? revision.getId() : oldPublishedRevisionId);
        outboxService.append("ARTICLE", article.getId(), aggregateVersion,
                article.getLifecycleEpoch(), eventType, 1, payload,
                "ARTICLE:" + article.getId() + ":" + article.getLifecycleEpoch() + ":"
                        + aggregateVersion + ":" + eventType.name());
        return get(jobId);
    }

    private void validateDecisionTuple(ArticleModerationJob hint, ArticleModerationJob job,
                                       Article article, ArticleRevision revision,
                                       ModerationDecisionRequest request) {
        boolean valid = job != null && article != null && revision != null
                && hint.getArticleId().equals(article.getId())
                && hint.getRevisionId().equals(job.getRevisionId())
                && hint.getContentHash().equals(job.getContentHash())
                && job.getArticleId().equals(article.getId())
                && job.getRevisionId().equals(revision.getId())
                && request.revisionId() == revision.getId()
                && job.getContentHash().equals(revision.getContentHash())
                && "HUMAN_PENDING".equals(job.getState())
                && job.getLeaseOwner() == null
                && job.getLeaseUntil() == null
                && job.getLockVersion() == request.expectedJobVersion()
                && article.getLockVersion() == request.expectedArticleVersion()
                && revision.getId().equals(article.getPendingRevisionId())
                && Integer.valueOf(0).equals(article.getIsDeleted())
                && integrityVerifier.verify(revision).isPresent();
        if (!valid) {
            throw AiApiException.optimisticLockConflict();
        }
    }

    private static void putNullable(ObjectNode payload, String field, Long value) {
        if (value == null) {
            payload.putNull(field);
        }
        else {
            payload.put(field, value);
        }
    }

    private ModerationJobResponse response(ArticleModerationJob job) {
        Article article = articleMapper.selectById(job.getArticleId());
        ArticleRevision revision = revisionMapper.selectById(job.getRevisionId());
        if (article == null || revision == null
                || !job.getArticleId().equals(revision.getArticleId())
                || !job.getArticleId().equals(article.getId())
                || !job.getContentHash().equals(revision.getContentHash())) {
            throw AiApiException.optimisticLockConflict();
        }
        ArticleRevisionIntegrityVerifier.VerifiedRevision verified = integrityVerifier.verify(revision)
                .orElseThrow(AiApiException::optimisticLockConflict);
        return new ModerationJobResponse(
                job.getId(), job.getArticleId(), job.getRevisionId(), job.getContentHash(), job.getState(),
                job.getModelDecision(), job.getRiskScore(), jsonOrNull(job.getPolicyHitsJson()),
                job.getAttemptCount(), job.getLastError(), job.getLockVersion(), article.getLockVersion(),
                article.getLifecycleEpoch(), article.getPublishedRevisionId(),
                new ModerationRevisionResponse(revision.getId(), revision.getRevisionNo(),
                        revision.getTitle(), revision.getSummary(), revision.getBodyMarkdown(),
                        revision.getBodyPlain(), revision.getCover(), verified.tags(),
                        revision.getContentHash(), revision.getSourceDraftVersion(), revision.getCreatedBy(),
                        revision.getCreatedAt()));
    }

    private JsonNode jsonOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        }
        catch (java.io.IOException invalidStoredJson) {
            throw AiApiException.optimisticLockConflict();
        }
    }
}
