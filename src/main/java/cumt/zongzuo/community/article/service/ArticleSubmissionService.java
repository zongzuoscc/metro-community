package cumt.zongzuo.community.article.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.moderation.revision.ArticleModerationJob;
import cumt.zongzuo.community.ai.moderation.revision.ArticleModerationJobMapper;
import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.article.model.ArticleContentSnapshot;
import cumt.zongzuo.community.article.model.ArticleDraft;
import cumt.zongzuo.community.article.model.ArticleRevision;
import cumt.zongzuo.community.article.persistence.ArticleDraftMapper;
import cumt.zongzuo.community.article.persistence.ArticleRevisionMapper;
import cumt.zongzuo.community.article.web.SubmissionResult;
import cumt.zongzuo.community.article.web.SubmitArticleRevisionCommand;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.outbox.DomainEventOutboxService;
import cumt.zongzuo.community.mapper.ArticleMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class ArticleSubmissionService {

    private static final String AGGREGATE_TYPE = "ARTICLE";

    private final ArticleRevisionModeResolver modeResolver;
    private final ArticleMapper articleMapper;
    private final ArticleDraftMapper draftMapper;
    private final ArticleRevisionMapper revisionMapper;
    private final ArticleModerationJobMapper jobMapper;
    private final ArticleContentCanonicalizer canonicalizer;
    private final DomainEventOutboxService outboxService;
    private final MetroAiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final ArticleLegacyTagWriter legacyTagWriter;

    public ArticleSubmissionService(ArticleRevisionModeResolver modeResolver,
                                    ArticleMapper articleMapper,
                                    ArticleDraftMapper draftMapper,
                                    ArticleRevisionMapper revisionMapper,
                                    ArticleModerationJobMapper jobMapper,
                                    ArticleContentCanonicalizer canonicalizer,
                                    DomainEventOutboxService outboxService,
                                    MetroAiProperties aiProperties,
                                    ObjectMapper objectMapper,
                                    ArticleLegacyTagWriter legacyTagWriter) {
        this.modeResolver = modeResolver;
        this.articleMapper = articleMapper;
        this.draftMapper = draftMapper;
        this.revisionMapper = revisionMapper;
        this.jobMapper = jobMapper;
        this.canonicalizer = canonicalizer;
        this.outboxService = outboxService;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.legacyTagWriter = legacyTagWriter;
    }

    @Transactional(rollbackFor = Exception.class)
    public SubmissionResult submit(SubmitArticleRevisionCommand command) {
        requireRevisionWriteMode();
        Article article = articleMapper.selectByIdForUpdate(command.articleId());
        if (article == null || !Long.valueOf(command.userId()).equals(article.getAuthorId())) {
            throw notFound();
        }
        if (!Integer.valueOf(0).equals(article.getIsDeleted())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ARTICLE_RECYCLED");
        }
        if (modeResolver.current() == ArticleRevisionMode.SHADOW
                && Integer.valueOf(1).equals(article.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PUBLISHED_ARTICLE_EDIT_REQUIRES_CUTOVER");
        }

        ArticleDraft draft = draftMapper.selectOwnerDraftForUpdate(command.articleId(), command.userId());
        if (draft == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DRAFT_REQUIRED");
        }
        if (draft.getDraftVersion() != command.expectedDraftVersion()) {
            throw optimisticConflict();
        }
        ArticleContentSnapshot snapshot = canonicalizer.canonicalize(draft.getTitle(), draft.getSummary(),
                draft.getBodyMarkdown(), draft.getCover(), readTags(draft.getTagsJson()));
        if (!snapshot.contentHash().equals(draft.getContentHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DRAFT_HASH_MISMATCH");
        }
        if (snapshot.tags().size() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ARTICLE_TAG_LIMIT_EXCEEDED");
        }
        if (modeResolver.current() == ArticleRevisionMode.SHADOW) {
            legacyTagWriter.replace(article.getId(), snapshot.tags());
        }

        List<ArticleModerationJob> olderJobs = jobMapper.selectNonTerminalForUpdate(command.articleId()).stream()
                .sorted(Comparator.comparingLong(ArticleModerationJob::getId))
                .toList();
        LocalDateTime now = LocalDateTime.now();
        long revisionNo = revisionMapper.selectNextRevisionNo(command.articleId());
        ArticleRevision revision = revision(snapshot, command, revisionNo, now);
        if (revisionMapper.insert(revision) != 1) {
            throw new IllegalStateException("revision insert failed");
        }
        for (ArticleModerationJob olderJob : olderJobs) {
            if (jobMapper.supersede(olderJob.getId(), olderJob.getLockVersion(), now) != 1) {
                throw optimisticConflict();
            }
        }

        ArticleModerationJob job = moderationJob(command.articleId(), revision, now);
        if (jobMapper.insert(job) != 1) {
            throw new IllegalStateException("moderation job insert failed");
        }

        int eventCount = olderJobs.isEmpty() ? 1 : 2;
        long baseVersion = article.getLockVersion();
        String visibility = article.getPublishedRevisionId() == null ? "PRIVATE" : "PUBLIC";
        int legacyStatus = article.getPublishedRevisionId() == null ? 2 : 1;
        if (articleMapper.updateSubmissionPointers(article.getId(), article.getAuthorId(), revision.getId(),
                visibility, job.getState(), legacyStatus, eventCount, baseVersion, now) != 1) {
            throw optimisticConflict();
        }

        int eventOffset = 0;
        if (!olderJobs.isEmpty()) {
            long supersededVersion = baseVersion + 1;
            outboxService.append(AGGREGATE_TYPE, article.getId(), supersededVersion,
                    article.getLifecycleEpoch(), DomainEventType.ARTICLE_REVISION_SUPERSEDED, 1,
                    supersededPayload(article.getId(), revision.getId(), olderJobs),
                    dedupeKey(article, supersededVersion, DomainEventType.ARTICLE_REVISION_SUPERSEDED));
            eventOffset = 1;
        }
        long submittedVersion = baseVersion + eventOffset + 1;
        outboxService.append(AGGREGATE_TYPE, article.getId(), submittedVersion,
                article.getLifecycleEpoch(), DomainEventType.ARTICLE_REVISION_SUBMITTED, 1,
                submittedPayload(article, revision, job),
                dedupeKey(article, submittedVersion, DomainEventType.ARTICLE_REVISION_SUBMITTED));

        return new SubmissionResult(article.getId(), revision.getId(), revisionNo, job.getId(),
                revision.getContentHash());
    }

    private void requireRevisionWriteMode() {
        ArticleRevisionMode mode = modeResolver.current();
        if (mode == ArticleRevisionMode.VERIFY_FENCE || mode == ArticleRevisionMode.POINTER_READ) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ARTICLE_CUTOVER_IN_PROGRESS");
        }
        if (mode == ArticleRevisionMode.LEGACY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVISION_WRITE_NOT_ENABLED");
        }
    }

    private ArticleRevision revision(ArticleContentSnapshot snapshot,
                                     SubmitArticleRevisionCommand command,
                                     long revisionNo,
                                     LocalDateTime now) {
        ArticleRevision revision = new ArticleRevision();
        revision.setArticleId(command.articleId());
        revision.setRevisionNo(revisionNo);
        revision.setTitle(snapshot.title());
        revision.setSummary(snapshot.summary());
        revision.setBodyMarkdown(snapshot.bodyMarkdown());
        revision.setBodyPlain(snapshot.bodyPlain());
        revision.setCover(snapshot.cover());
        revision.setTagsJson(snapshot.tagsJson());
        revision.setContentHash(snapshot.contentHash());
        revision.setSourceDraftVersion(command.expectedDraftVersion());
        revision.setCreatedBy(command.userId());
        revision.setCreatedAt(now);
        return revision;
    }

    private ArticleModerationJob moderationJob(long articleId, ArticleRevision revision, LocalDateTime now) {
        ArticleModerationJob job = new ArticleModerationJob();
        job.setArticleId(articleId);
        job.setRevisionId(revision.getId());
        job.setContentHash(revision.getContentHash());
        job.setState(moderationAvailable() ? "PENDING" : "HUMAN_PENDING");
        job.setAttemptCount(0);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setLockVersion(0L);
        return job;
    }

    private boolean moderationAvailable() {
        return aiProperties.isCapabilityEnabled(AiCapability.MODERATION)
                && StringUtils.hasText(aiProperties.getDeepSeek().getApiKey())
                && StringUtils.hasText(aiProperties.getDeepSeek().getBaseUrl())
                && StringUtils.hasText(aiProperties.getDeepSeek().getModel());
    }

    private JsonNode submittedPayload(Article article, ArticleRevision revision, ArticleModerationJob job) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("articleId", article.getId());
        payload.put("revisionId", revision.getId());
        payload.put("revisionNo", revision.getRevisionNo());
        payload.put("moderationJobId", job.getId());
        payload.put("contentHash", revision.getContentHash());
        payload.put("sourceDraftVersion", revision.getSourceDraftVersion());
        return payload;
    }

    private JsonNode supersededPayload(long articleId, long replacementRevisionId,
                                       List<ArticleModerationJob> olderJobs) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("articleId", articleId);
        payload.put("replacementRevisionId", replacementRevisionId);
        ArrayNode jobIds = payload.putArray("supersededJobIds");
        ArrayNode revisionIds = payload.putArray("supersededRevisionIds");
        ArrayNode contentHashes = payload.putArray("supersededContentHashes");
        for (ArticleModerationJob olderJob : olderJobs) {
            jobIds.add(olderJob.getId());
            revisionIds.add(olderJob.getRevisionId());
            contentHashes.add(olderJob.getContentHash());
        }
        return payload;
    }

    private List<String> readTags(String tagsJson) {
        try {
            return objectMapper.readerForListOf(String.class).readValue(tagsJson);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DRAFT_TAGS_INVALID", exception);
        }
    }

    private static String dedupeKey(Article article, long version, DomainEventType type) {
        return AGGREGATE_TYPE + ":" + article.getId() + ":" + article.getLifecycleEpoch()
                + ":" + version + ":" + type.name();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND");
    }

    private static ResponseStatusException optimisticConflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT");
    }
}
