package cumt.zongzuo.community.ai.moderation.revision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.article.model.ArticleRevision;
import cumt.zongzuo.community.article.persistence.ArticleRevisionMapper;
import cumt.zongzuo.community.article.service.ArticleRevisionIntegrityVerifier;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.mapper.ArticleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ArticleModerationStateMachine {

    private static final String MODEL_PREFIX = "MODEL_";

    private final ArticleModerationJobMapper jobMapper;
    private final ArticleModerationAttemptMapper attemptMapper;
    private final ArticleRevisionMapper revisionMapper;
    private final ArticleMapper articleMapper;
    private final ArticleRevisionIntegrityVerifier integrityVerifier;
    private final ObjectMapper objectMapper;
    private final MetroAiProperties properties;
    private final Clock clock;

    public ArticleModerationStateMachine(ArticleModerationJobMapper jobMapper,
                                         ArticleModerationAttemptMapper attemptMapper,
                                         ArticleRevisionMapper revisionMapper,
                                         ArticleMapper articleMapper,
                                         ArticleRevisionIntegrityVerifier integrityVerifier,
                                         ObjectMapper objectMapper,
                                         MetroAiProperties properties,
                                         Clock clock) {
        this.jobMapper = jobMapper;
        this.attemptMapper = attemptMapper;
        this.revisionMapper = revisionMapper;
        this.articleMapper = articleMapper;
        this.integrityVerifier = integrityVerifier;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Optional<ModerationJobLease> claim(DomainEvent event) {
        Submission submission = submission(event);
        // Submission, restore and recycle all fence the aggregate through the article row.
        // Take that same lock before touching the job so a worker can never observe a job
        // from one aggregate version and an article pointer from another.
        Article article = articleMapper.selectByIdForUpdate(submission.articleId());
        ArticleRevision revision = revisionMapper.selectById(submission.revisionId());
        if (!validSubmissionBinding(event, submission, article, revision)) {
            supersedeUnclaimedStaleJob(submission);
            return Optional.empty();
        }
        String owner = "moderation-" + UUID.randomUUID();
        long leaseMicros = properties.getModeration().getLeaseDuration().toNanos() / 1_000L;
        if (jobMapper.claimRevision(submission.jobId(), submission.articleId(),
                submission.revisionId(), submission.contentHash(), owner, leaseMicros) != 1) {
            return Optional.empty();
        }
        ArticleModerationJob job = jobMapper.selectByIdForUpdate(submission.jobId());
        if (job == null) {
            throw new IllegalStateException("claimed moderation job disappeared");
        }
        long authorId = article.getAuthorId();
        return Optional.of(new ModerationJobLease(job.getId(), submission.articleId(),
                submission.revisionId(), submission.contentHash(), owner, job.getLockVersion(),
                event.aggregateVersion(), event.lifecycleEpoch(), authorId,
                job.getAttemptCount(),
                clock.instant().plus(properties.getModeration().getTaskTimeout())));
    }

    private boolean validSubmissionBinding(DomainEvent event, Submission submission,
                                           Article article, ArticleRevision revision) {
        return article != null && revision != null
                && Long.valueOf(submission.articleId()).equals(revision.getArticleId())
                && Long.valueOf(submission.revisionId()).equals(article.getPendingRevisionId())
                && Long.valueOf(event.aggregateVersion()).equals(article.getLockVersion())
                && Long.valueOf(event.lifecycleEpoch()).equals(article.getLifecycleEpoch())
                && article.getAuthorId() != null
                && article.getAuthorId().equals(revision.getCreatedBy())
                && Integer.valueOf(0).equals(article.getIsDeleted())
                && submission.contentHash().equals(revision.getContentHash())
                && submission.contentHash().equals(freshHash(revision));
    }

    private void supersedeUnclaimedStaleJob(Submission submission) {
        ArticleModerationJob job = jobMapper.selectByIdForUpdate(submission.jobId());
        if (job == null
                || !Long.valueOf(submission.articleId()).equals(job.getArticleId())
                || !Long.valueOf(submission.revisionId()).equals(job.getRevisionId())
                || !submission.contentHash().equals(job.getContentHash())) {
            return;
        }
        jobMapper.supersede(job.getId(), job.getLockVersion(),
                LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()));
    }

    @Transactional
    public void routeUnavailable(DomainEvent event, String errorCode) {
        Submission submission = submission(event);
        Article article = articleMapper.selectByIdForUpdate(submission.articleId());
        ArticleRevision revision = revisionMapper.selectById(submission.revisionId());
        if (!validSubmissionBinding(event, submission, article, revision)) {
            supersedeUnclaimedStaleJob(submission);
            return;
        }
        jobMapper.routeUnavailableToHumanPending(submission.jobId(), submission.articleId(),
                submission.revisionId(), submission.contentHash(), safeError(errorCode));
    }

    @Transactional(readOnly = true)
    public boolean isBusy(DomainEvent event) {
        Submission submission = submission(event);
        return jobMapper.isActiveBusy(submission.jobId(), submission.articleId(),
                submission.revisionId(), submission.contentHash()) == 1;
    }

    @Transactional
    public boolean validate(ModerationJobLease lease) {
        return validateBinding(lease, true, false) != null;
    }

    @Transactional(readOnly = true)
    public ArticleRevision loadRevision(ModerationJobLease lease) {
        return revisionMapper.selectById(lease.revisionId());
    }

    @Transactional
    public Optional<ModerationInvocation> reserveAttempt(ModerationJobLease lease) {
        ArticleModerationJob job = validateBinding(lease, true, true);
        if (job == null) {
            return Optional.empty();
        }
        if (deadlineExpired(lease)) {
            deadlineToHumanPending(lease);
            return Optional.empty();
        }
        int previous = lease.attemptCount();
        int maximumAttempts = Math.multiplyExact(properties.getModeration().getMaxChunks(),
                properties.getRuntime().getBackgroundMaxAttempts());
        if (job.getAttemptCount() != previous || previous >= maximumAttempts) {
            if (previous >= maximumAttempts) {
                jobMapper.preProviderFailureToHumanPending(lease.jobId(), lease.owner(),
                        lease.jobLockVersion(), "ATTEMPT_BUDGET_EXHAUSTED");
            }
            return Optional.empty();
        }
        int attemptNo = Math.addExact(previous, 1);
        if (jobMapper.advanceAttempt(lease.jobId(), lease.owner(), lease.jobLockVersion(),
                previous, attemptNo) != 1) {
            throw new IllegalStateException("moderation attempt CAS failed");
        }
        ModerationJobLease reserved = lease.reserveAttempt();
        return Optional.of(new ModerationInvocation(reserved, attemptNo));
    }

    @Transactional
    public boolean recordSuccess(ModerationInvocation invocation, ModerationAttemptRecord record) {
        ModerationJobLease lease = invocation.lease();
        CompletionFence fence = completionFence(invocation);
        if (fence == CompletionFence.LOST) {
            return false;
        }
        if (fence == CompletionFence.STALE) {
            recordTerminalAttempt(invocation, sanitized(record, "STALE_REVISION_BINDING"),
                    "STALE_REVISION_BINDING", true);
            return false;
        }
        if (deadlineExpired(lease)) {
            recordTerminalAttempt(invocation, sanitized(record, "TASK_DEADLINE_EXCEEDED"),
                    "TASK_DEADLINE_EXCEEDED", false);
            return false;
        }
        insertAttempt(lease, invocation.attemptNo(), record);
        return true;
    }

    @Transactional
    public boolean recordAttemptFailure(ModerationInvocation invocation,
                                        ModerationAttemptRecord record) {
        ModerationJobLease lease = invocation.lease();
        CompletionFence fence = completionFence(invocation);
        if (fence == CompletionFence.LOST) {
            return false;
        }
        if (fence == CompletionFence.STALE) {
            recordTerminalAttempt(invocation, sanitized(record, "STALE_REVISION_BINDING"),
                    "STALE_REVISION_BINDING", true);
            return false;
        }
        if (deadlineExpired(lease)) {
            recordTerminalAttempt(invocation, sanitized(record, "TASK_DEADLINE_EXCEEDED"),
                    "TASK_DEADLINE_EXCEEDED", false);
            return false;
        }
        insertAttempt(lease, invocation.attemptNo(), record);
        return true;
    }

    @Transactional
    public void recordPreProviderFailure(ModerationJobLease lease, String errorCode) {
        if (validateBinding(lease, true, true) == null) {
            return;
        }
        if (deadlineExpired(lease)) {
            errorCode = "TASK_DEADLINE_EXCEEDED";
        }
        if (jobMapper.preProviderFailureToHumanPending(lease.jobId(), lease.owner(),
                lease.jobLockVersion(), safeError(errorCode)) != 1) {
            throw new IllegalStateException("moderation pre-provider failure CAS failed");
        }
    }

    @Transactional
    public void finishShadow(ModerationJobLease lease, ModerationAggregate aggregate) {
        if (validateBinding(lease, true, true) == null) {
            return;
        }
        if (deadlineExpired(lease)) {
            deadlineToHumanPending(lease);
            return;
        }
        String modelState = MODEL_PREFIX + aggregate.decision().name();
        String errorCode = aggregate.uncertain() ? "MODEL_UNCERTAIN" : null;
        if (jobMapper.recordModelTransition(lease.jobId(), lease.owner(), lease.jobLockVersion(),
                modelState, aggregate.decision().name(), aggregate.riskScore(),
                policyHits(aggregate), errorCode) != 1) {
            throw new IllegalStateException("moderation model evidence CAS failed");
        }
        if (jobMapper.modelToHumanPending(lease.jobId(), lease.owner(),
                lease.jobLockVersion() + 1, modelState) != 1) {
            throw new IllegalStateException("moderation shadow handoff CAS failed");
        }
    }

    private ArticleModerationJob validateBinding(ModerationJobLease lease, boolean supersede,
                                                  boolean lockForWrite) {
        Article article;
        ArticleModerationJob job;
        ArticleRevision revision;
        if (lockForWrite) {
            // All moderation writers follow the article -> job -> immutable revision lock order.
            article = articleMapper.selectByIdForUpdate(lease.articleId());
            job = jobMapper.selectByIdForUpdate(lease.jobId());
            revision = revisionMapper.selectById(lease.revisionId());
        }
        else {
            job = jobMapper.selectById(lease.jobId());
            revision = revisionMapper.selectById(lease.revisionId());
            article = articleMapper.selectById(lease.articleId());
        }
        boolean validLease = jobMapper.hasValidLease(lease.jobId(), lease.owner(),
                lease.jobLockVersion()) == 1;
        boolean valid = validLease && matchesBinding(lease, job, revision, article);
        if (!valid && supersede && validLease) {
            jobMapper.supersedeOwned(lease.jobId(), lease.owner(), lease.jobLockVersion(),
                    "STALE_REVISION_BINDING");
        }
        return valid ? job : null;
    }

    private boolean matchesBinding(ModerationJobLease lease, ArticleModerationJob job,
                                   ArticleRevision revision, Article article) {
        return job != null && revision != null && article != null
                && Long.valueOf(lease.articleId()).equals(job.getArticleId())
                && Long.valueOf(lease.revisionId()).equals(job.getRevisionId())
                && lease.contentHash().equals(job.getContentHash())
                && Long.valueOf(lease.articleId()).equals(revision.getArticleId())
                && lease.contentHash().equals(revision.getContentHash())
                && Long.valueOf(lease.revisionId()).equals(article.getPendingRevisionId())
                && Long.valueOf(lease.articleLockVersion()).equals(article.getLockVersion())
                && Long.valueOf(lease.lifecycleEpoch()).equals(article.getLifecycleEpoch())
                && Long.valueOf(lease.authorId()).equals(article.getAuthorId())
                && Long.valueOf(lease.authorId()).equals(revision.getCreatedBy())
                && Integer.valueOf(0).equals(article.getIsDeleted())
                && freshHash(revision).equals(lease.contentHash());
    }

    private CompletionFence completionFence(ModerationInvocation invocation) {
        ModerationJobLease lease = invocation.lease();
        Article article = articleMapper.selectByIdForUpdate(lease.articleId());
        ArticleModerationJob job = jobMapper.selectByIdForUpdate(lease.jobId());
        ArticleRevision revision = revisionMapper.selectById(lease.revisionId());
        boolean validLease = jobMapper.hasValidLease(lease.jobId(), lease.owner(),
                lease.jobLockVersion()) == 1;
        if (!validLease || job == null || job.getAttemptCount() != invocation.attemptNo()) {
            return CompletionFence.LOST;
        }
        return matchesBinding(lease, job, revision, article)
                ? CompletionFence.VALID : CompletionFence.STALE;
    }

    private void recordTerminalAttempt(ModerationInvocation invocation,
                                       ModerationAttemptRecord record,
                                       String errorCode, boolean supersede) {
        ModerationJobLease lease = invocation.lease();
        insertAttempt(lease, invocation.attemptNo(), record);
        int changed = supersede
                ? jobMapper.supersedeOwned(lease.jobId(), lease.owner(),
                        lease.jobLockVersion(), errorCode)
                : jobMapper.failToHumanPending(lease.jobId(), lease.owner(),
                        lease.jobLockVersion(), invocation.attemptNo(), errorCode);
        if (changed != 1) {
            throw new IllegalStateException("moderation terminal attempt CAS failed");
        }
    }

    private static ModerationAttemptRecord sanitized(ModerationAttemptRecord record,
                                                     String errorCode) {
        return new ModerationAttemptRecord(record.inputHash(), record.chunk(), record.latencyMs(),
                record.result(), null, errorCode);
    }

    private boolean deadlineExpired(ModerationJobLease lease) {
        return !clock.instant().isBefore(lease.taskDeadline());
    }

    private void deadlineToHumanPending(ModerationJobLease lease) {
        if (jobMapper.preProviderFailureToHumanPending(lease.jobId(), lease.owner(),
                lease.jobLockVersion(), "TASK_DEADLINE_EXCEEDED") != 1) {
            throw new IllegalStateException("moderation deadline CAS failed");
        }
    }

    private String freshHash(ArticleRevision revision) {
        return integrityVerifier.freshHashOrEmpty(revision);
    }

    private void insertAttempt(ModerationJobLease lease, int attemptNo,
                               ModerationAttemptRecord record) {
        ArticleModerationAttempt row = new ArticleModerationAttempt();
        row.setJobId(lease.jobId());
        row.setAttemptNo(attemptNo);
        AiChatResult result = record.result();
        row.setProvider(result == null ? "deepseek" : result.provider());
        row.setModel(result == null ? properties.getDeepSeek().getModel() : result.model());
        row.setPromptVersion(ModerationPromptFactory.PROMPT_VERSION);
        row.setInputHash(record.inputHash());
        row.setStructuredOutputJson(structuredEvidence(record));
        row.setLatencyMs(Math.max(0L, record.latencyMs()));
        row.setTokenUsageJson(result == null ? null : tokenUsage(result));
        row.setFinishReason(result == null ? null : result.finishReason());
        row.setErrorCode(record.errorCode() == null ? null : safeError(record.errorCode()));
        row.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()));
        if (attemptMapper.insert(row) != 1) {
            throw new IllegalStateException("moderation attempt insert failed");
        }
    }

    private String policyHits(ModerationAggregate aggregate) {
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode categories = node.putArray("categories");
        aggregate.categories().stream().sorted(Comparator.comparing(Enum::name))
                .forEach(category -> categories.add(category.name()));
        node.put("severity", aggregate.severity());
        node.put("confidence", aggregate.confidence());
        node.put("uncertain", aggregate.uncertain());
        return writeJson(node);
    }

    private String structuredEvidence(ModerationAttemptRecord record) {
        ModerationChunk chunk = record.chunk();
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode chunkNode = root.putObject("chunk");
        chunkNode.put("index", chunk.index());
        chunkNode.put("sourceStart", chunk.sourceStart());
        chunkNode.put("sourceEnd", chunk.sourceEnd());
        chunkNode.put("estimatedInputTokens", chunk.estimatedTokens());
        ModerationModelOutput output = record.output();
        if (output != null) {
            ObjectNode model = root.putObject("modelOutput");
            model.put("decision", output.decision().name());
            ArrayNode categories = model.putArray("categories");
            output.categories().stream().sorted(Comparator.comparing(Enum::name))
                    .forEach(category -> categories.add(category.name()));
            model.put("severity", output.severity());
            model.put("confidence", output.confidence());
            ArrayNode evidence = model.putArray("evidenceOffsets");
            for (ModerationEvidence offset : output.evidenceOffsets()) {
                ObjectNode range = evidence.addObject();
                range.put("start", Math.addExact(chunk.sourceStart(), offset.start()));
                range.put("end", Math.addExact(chunk.sourceStart(), offset.end()));
            }
            model.put("reason", output.reason());
            model.put("model", output.model());
            model.put("promptVersion", output.promptVersion());
        }
        return writeJson(root);
    }

    private String tokenUsage(AiChatResult result) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("inputTokens", result.inputTokens());
        node.put("outputTokens", result.outputTokens());
        return writeJson(node);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException error) {
            throw new IllegalArgumentException("moderation evidence cannot be serialized", error);
        }
    }

    private static Submission submission(DomainEvent event) {
        if (event.eventType() != DomainEventType.ARTICLE_REVISION_SUBMITTED
                || !"ARTICLE".equals(event.aggregateType()) || event.payloadVersion() != 1
                || !event.payload().isObject()) {
            throw new IllegalArgumentException("unsupported moderation event");
        }
        JsonNode payload = event.payload();
        if (!positiveLong(payload, "articleId") || !positiveLong(payload, "revisionId")
                || !positiveLong(payload, "moderationJobId")
                || !payload.path("contentHash").isTextual()) {
            throw new IllegalArgumentException("moderation event tuple is incomplete");
        }
        long articleId = payload.path("articleId").longValue();
        long revisionId = payload.path("revisionId").longValue();
        long jobId = payload.path("moderationJobId").longValue();
        String hash = payload.path("contentHash").textValue();
        if (articleId != event.aggregateId() || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("moderation event tuple is invalid");
        }
        return new Submission(articleId, revisionId, jobId, hash);
    }

    private static boolean positiveLong(JsonNode payload, String field) {
        return payload.path(field).isIntegralNumber() && payload.path(field).canConvertToLong()
                && payload.path(field).longValue() > 0;
    }

    private static String safeError(String errorCode) {
        if (errorCode == null) {
            return null;
        }
        return errorCode.matches("[A-Z0-9_]{1,64}") ? errorCode : "INTERNAL_ERROR";
    }

    private record Submission(long articleId, long revisionId, long jobId, String contentHash) {
    }

    private enum CompletionFence {
        VALID,
        STALE,
        LOST
    }
}
