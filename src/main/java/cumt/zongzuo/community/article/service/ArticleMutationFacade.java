package cumt.zongzuo.community.article.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.article.model.ArticleContentSnapshot;
import cumt.zongzuo.community.article.model.ArticleDraft;
import cumt.zongzuo.community.article.persistence.ArticleDraftMapper;
import cumt.zongzuo.community.article.persistence.ArticleRevisionMapper;
import cumt.zongzuo.community.article.web.SaveArticleDraftCommand;
import cumt.zongzuo.community.article.web.SubmissionResult;
import cumt.zongzuo.community.article.web.SubmitArticleRevisionCommand;
import cumt.zongzuo.community.dto.ArticleDTO;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.entity.ArticleTag;
import cumt.zongzuo.community.entity.Tag;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.ArticleTagMapper;
import cumt.zongzuo.community.mapper.TagMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.util.StringUtils;
import cumt.zongzuo.community.utils.SensitiveUtils;
import cumt.zongzuo.community.ai.moderation.revision.ArticleModerationJob;
import cumt.zongzuo.community.ai.moderation.revision.ArticleModerationJobMapper;
import cumt.zongzuo.community.article.model.ArticleRevision;
import cumt.zongzuo.community.dto.NotificationMsgDTO;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.outbox.DomainEventOutboxService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ArticleMutationFacade implements ArticleDraftService {

    private final ArticleRevisionModeResolver modeResolver;
    private final ArticleMutationGate mutationGate;
    private final ArticleMapper articleMapper;
    private final ArticleDraftMapper draftMapper;
    private final ArticleTagMapper articleTagMapper;
    private final TagMapper tagMapper;
    private final ArticleContentCanonicalizer canonicalizer;
    private final ArticleSubmissionService submissionService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final SensitiveUtils sensitiveUtils;
    private final ArticleRevisionMapper revisionMapper;
    private final ArticleModerationJobMapper jobMapper;
    private final DomainEventOutboxService outboxService;
    private final ArticleLegacyTagWriter legacyTagWriter;

    public ArticleMutationFacade(ArticleRevisionModeResolver modeResolver,
                                 ArticleMutationGate mutationGate,
                                 ArticleMapper articleMapper,
                                 ArticleDraftMapper draftMapper,
                                 ArticleTagMapper articleTagMapper,
                                 TagMapper tagMapper,
                                 ArticleContentCanonicalizer canonicalizer,
                                 ArticleSubmissionService submissionService,
                                 com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                 RabbitTemplate rabbitTemplate,
                                 SensitiveUtils sensitiveUtils,
                                 ArticleRevisionMapper revisionMapper,
                                 ArticleModerationJobMapper jobMapper,
                                 DomainEventOutboxService outboxService,
                                 ArticleLegacyTagWriter legacyTagWriter) {
        this.modeResolver = modeResolver;
        this.mutationGate = mutationGate;
        this.articleMapper = articleMapper;
        this.draftMapper = draftMapper;
        this.articleTagMapper = articleTagMapper;
        this.tagMapper = tagMapper;
        this.canonicalizer = canonicalizer;
        this.submissionService = submissionService;
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.sensitiveUtils = sensitiveUtils;
        this.revisionMapper = revisionMapper;
        this.jobMapper = jobMapper;
        this.outboxService = outboxService;
        this.legacyTagWriter = legacyTagWriter;
    }

    public SubmissionResult submit(SubmitArticleRevisionCommand command) {
        return submissionService.submit(command);
    }

    public void assertArticleWritesAllowed() {
        requireAnyArticleWriteAllowed();
    }

    @Transactional(rollbackFor = Exception.class)
    public void rejectReportedArticle(long articleId) {
        requireAnyArticleWriteAllowed();
        Article article = articleMapper.selectByIdForUpdate(articleId);
        if (article == null || !Integer.valueOf(0).equals(article.getIsDeleted())) {
            return;
        }
        ArticleRevisionMode mode = modeResolver.current();
        LocalDateTime now = LocalDateTime.now();
        if (mode == ArticleRevisionMode.LEGACY) {
            if (Integer.valueOf(3).equals(article.getStatus())) {
                return;
            }
            int lifecycleIncrement = Integer.valueOf(1).equals(article.getStatus()) ? 1 : 0;
            if (articleMapper.rejectReportedLegacy(articleId, lifecycleIncrement,
                    article.getLockVersion(), now) != 1) {
                throw optimisticConflict();
            }
            return;
        }

        article = ensureCanonicalShadow(article).article();
        if (Integer.valueOf(3).equals(article.getStatus())
                && "PRIVATE".equals(article.getVisibilityState())
                && "REJECTED".equals(article.getReviewState())
                && article.getPublishedRevisionId() == null
                && article.getPendingRevisionId() == null) {
            return;
        }
        List<ArticleModerationJob> supersededJobs = jobMapper.selectNonTerminalForUpdate(articleId).stream()
                .sorted(Comparator.comparingLong(ArticleModerationJob::getId))
                .toList();
        for (ArticleModerationJob job : supersededJobs) {
            if (jobMapper.supersede(job.getId(), job.getLockVersion(), now) != 1) {
                throw optimisticConflict();
            }
        }

        int eventCount = supersededJobs.isEmpty() ? 1 : 2;
        long baseVersion = article.getLockVersion();
        Long oldPublishedRevisionId = article.getPublishedRevisionId();
        int lifecycleIncrement = oldPublishedRevisionId == null ? 0 : 1;
        long decisionEpoch = article.getLifecycleEpoch() + lifecycleIncrement;
        Long rejectedRevisionId = article.getPendingRevisionId() != null
                ? article.getPendingRevisionId() : article.getLatestRevisionId();
        if (articleMapper.rejectReportedShadow(articleId, lifecycleIncrement,
                eventCount, baseVersion, now) != 1) {
            throw optimisticConflict();
        }

        int eventOffset = 0;
        if (!supersededJobs.isEmpty()) {
            long supersededVersion = baseVersion + 1;
            outboxService.append("ARTICLE", articleId, supersededVersion, article.getLifecycleEpoch(),
                    DomainEventType.ARTICLE_REVISION_SUPERSEDED, 1,
                    supersededPayload(articleId, rejectedRevisionId, supersededJobs),
                    eventDedupe(article, supersededVersion, DomainEventType.ARTICLE_REVISION_SUPERSEDED));
            eventOffset = 1;
        }

        long decisionVersion = baseVersion + eventOffset + 1;
        DomainEventType decisionType = oldPublishedRevisionId == null
                ? DomainEventType.ARTICLE_REVISION_REJECTED : DomainEventType.ARTICLE_UNPUBLISHED;
        com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
        payload.put("articleId", articleId);
        payload.put("transition", "REPORT_CONFIRMED");
        if (rejectedRevisionId == null) {
            payload.putNull("revisionId");
        } else {
            payload.put("revisionId", rejectedRevisionId);
        }
        if (oldPublishedRevisionId == null) {
            payload.putNull("oldPublishedRevisionId");
        } else {
            payload.put("oldPublishedRevisionId", oldPublishedRevisionId);
        }
        payload.putNull("newPublishedRevisionId");
        outboxService.append("ARTICLE", articleId, decisionVersion, decisionEpoch,
                decisionType, 1, payload,
                eventDedupe(articleId, decisionEpoch, decisionVersion, decisionType));
    }

    @Transactional(rollbackFor = Exception.class)
    public void addLikeCount(long articleId, int delta) {
        requireAnyArticleWriteAllowed();
        Article article = articleMapper.selectByIdForUpdate(articleId);
        if (article != null && Integer.valueOf(0).equals(article.getIsDeleted())
                && articleMapper.addLikeCount(articleId, delta) != 1) {
            throw optimisticConflict();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void addCommentCount(long articleId, int delta) {
        requireAnyArticleWriteAllowed();
        Article article = articleMapper.selectByIdForUpdate(articleId);
        if (article != null && Integer.valueOf(0).equals(article.getIsDeleted())
                && articleMapper.addCommentCount(articleId, delta) != 1) {
            throw optimisticConflict();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void syncViewCount(long articleId, int viewCount) {
        requireAnyArticleWriteAllowed();
        Article article = articleMapper.selectByIdForUpdate(articleId);
        if (article != null && Integer.valueOf(0).equals(article.getIsDeleted())
                && articleMapper.setViewCount(articleId, viewCount) != 1) {
            throw optimisticConflict();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void auditLegacyArticle(long articleId, boolean pass, String reason, long administratorId) {
        ArticleRevisionMode mode = mutationGate.requireLegacyModerationDecisionAllowed();
        Article article = articleMapper.selectByIdForUpdate(articleId);
        int targetStatus = pass ? 1 : 3;
        if (article != null && Integer.valueOf(0).equals(article.getIsDeleted())
                && Integer.valueOf(targetStatus).equals(article.getStatus())) {
            return;
        }
        if (article == null || !Integer.valueOf(0).equals(article.getIsDeleted())
                || !Integer.valueOf(2).equals(article.getStatus())) {
            throw optimisticConflict();
        }
        if (mode == ArticleRevisionMode.LEGACY) {
            if (articleMapper.updateLegacyModerationDecision(articleId, targetStatus,
                    article.getLockVersion(), LocalDateTime.now()) != 1) {
                throw optimisticConflict();
            }
            if (pass) {
                rabbitTemplate.convertAndSend("es.sync.queue", articleId);
            }
            sendLegacyNotification(administratorId, article, pass, reason);
            return;
        }

        article = ensureCanonicalShadow(article).article();
        Long pendingRevisionId = article.getPendingRevisionId();
        if (pendingRevisionId == null) {
            throw optimisticConflict();
        }
        ArticleModerationJob job = jobMapper.selectRevisionJobForUpdate(articleId, pendingRevisionId);
        ArticleRevision revision = revisionMapper.selectById(pendingRevisionId);
        if (job == null || revision == null || !job.getContentHash().equals(revision.getContentHash())) {
            throw optimisticConflict();
        }
        LocalDateTime now = LocalDateTime.now();
        String finalJobState = pass ? "HUMAN_APPROVED" : "HUMAN_REJECTED";
        if (jobMapper.decideLegacyShadowJob(job.getId(), revision.getId(), revision.getContentHash(),
                job.getLockVersion(), finalJobState, administratorId, reason, now) != 1) {
            throw optimisticConflict();
        }
        Long publishedRevisionId = pass ? revision.getId() : article.getPublishedRevisionId();
        boolean remainsPublic = publishedRevisionId != null;
        int finalStatus = remainsPublic ? 1 : 3;
        String visibility = remainsPublic ? "PUBLIC" : "PRIVATE";
        String reviewState = pass ? "APPROVED" : "REJECTED";
        if (articleMapper.updateShadowModerationDecision(articleId, revision.getId(), publishedRevisionId,
                visibility, reviewState, finalStatus, article.getLockVersion(), now) != 1) {
            throw optimisticConflict();
        }
        long version = article.getLockVersion() + 1;
        DomainEventType type = pass
                ? DomainEventType.ARTICLE_REVISION_PUBLISHED : DomainEventType.ARTICLE_REVISION_REJECTED;
        com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
        payload.put("articleId", articleId);
        payload.put("revisionId", revision.getId());
        payload.put("moderationJobId", job.getId());
        payload.put("contentHash", revision.getContentHash());
        if (article.getPublishedRevisionId() == null) {
            payload.putNull("oldPublishedRevisionId");
        } else {
            payload.put("oldPublishedRevisionId", article.getPublishedRevisionId());
        }
        if (publishedRevisionId == null) {
            payload.putNull("newPublishedRevisionId");
        } else {
            payload.put("newPublishedRevisionId", publishedRevisionId);
        }
        outboxService.append("ARTICLE", articleId, version, article.getLifecycleEpoch(), type, 1,
                payload, eventDedupe(article, version, type));
    }

    @Transactional(rollbackFor = Exception.class)
    public void recycle(long articleId, long userId) {
        requireAnyArticleWriteAllowed();
        Article article = lockOwnedArticle(articleId, userId);
        if (!Integer.valueOf(0).equals(article.getIsDeleted())) {
            return;
        }
        ArticleRevisionMode mode = modeResolver.current();
        if (mode != ArticleRevisionMode.LEGACY) {
            article = ensureCanonicalShadow(article).article();
        }
        LocalDateTime now = LocalDateTime.now();
        if (mode == ArticleRevisionMode.LEGACY) {
            if (articleMapper.recycleLocked(articleId, userId, article.getVisibilityState(),
                    article.getLockVersion(), now) != 1) {
                throw optimisticConflict();
            }
            rabbitTemplate.convertAndSend("es.sync.queue", articleId);
        } else {
            List<ArticleModerationJob> supersededJobs = supersedeNonTerminalJobs(articleId, now);
            int eventCount = supersededJobs.isEmpty() ? 1 : 2;
            long baseVersion = article.getLockVersion();
            if (articleMapper.recycleRevisionModeLocked(articleId, userId, eventCount,
                    baseVersion, now) != 1) {
                throw optimisticConflict();
            }
            int eventOffset = 0;
            if (!supersededJobs.isEmpty()) {
                long supersededVersion = baseVersion + 1;
                outboxService.append("ARTICLE", articleId, supersededVersion,
                        article.getLifecycleEpoch(), DomainEventType.ARTICLE_REVISION_SUPERSEDED, 1,
                        supersededPayload(articleId, article.getPublishedRevisionId(), supersededJobs),
                        eventDedupe(article, supersededVersion,
                                DomainEventType.ARTICLE_REVISION_SUPERSEDED));
                eventOffset = 1;
            }
            appendLifecycleEvent(article, baseVersion + eventOffset + 1,
                    article.getLifecycleEpoch() + 1, DomainEventType.ARTICLE_DELETED, "RECYCLED");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void restore(long articleId, long userId) {
        requireAnyArticleWriteAllowed();
        Article article = lockArticleForOwnerIncludingDeleted(articleId, userId);
        if (Integer.valueOf(0).equals(article.getIsDeleted())) {
            return;
        }
        if ("PURGED".equals(article.getVisibilityState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ARTICLE_PURGED");
        }
        ArticleRevisionMode mode = modeResolver.current();
        if (mode != ArticleRevisionMode.LEGACY) {
            article = ensureCanonicalShadow(article).article();
        }
        String visibility = article.getPublishedRevisionId() == null ? "PRIVATE" : "PUBLIC";
        if (mode == ArticleRevisionMode.LEGACY) {
            visibility = article.getVisibilityState();
        }
        LocalDateTime now = LocalDateTime.now();
        if (articleMapper.restoreLocked(articleId, userId, visibility, article.getLockVersion(), now) != 1) {
            throw optimisticConflict();
        }
        if (mode == ArticleRevisionMode.LEGACY) {
            rabbitTemplate.convertAndSend("es.sync.queue", articleId);
        } else {
            DomainEventType type = article.getPublishedRevisionId() == null
                    ? DomainEventType.ARTICLE_UNPUBLISHED : DomainEventType.ARTICLE_REVISION_PUBLISHED;
            appendLifecycleEvent(article, article.getLockVersion() + 1,
                    article.getLifecycleEpoch(), type, "RESTORED");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void purge(long articleId, long userId) {
        requireAnyArticleWriteAllowed();
        Article article = lockArticleForOwnerIncludingDeleted(articleId, userId);
        if ("PURGED".equals(article.getVisibilityState())) {
            return;
        }
        if (!Integer.valueOf(1).equals(article.getIsDeleted())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ARTICLE_NOT_RECYCLED");
        }
        ArticleRevisionMode mode = modeResolver.current();
        if (mode != ArticleRevisionMode.LEGACY) {
            article = ensureCanonicalShadow(article).article();
        }
        LocalDateTime now = LocalDateTime.now();
        if (mode == ArticleRevisionMode.LEGACY) {
            if (articleMapper.purgeLocked(articleId, userId, article.getLockVersion(), now) != 1) {
                throw optimisticConflict();
            }
            rabbitTemplate.convertAndSend("es.sync.queue", articleId);
        } else {
            List<ArticleModerationJob> supersededJobs = supersedeNonTerminalJobs(articleId, now);
            int eventCount = supersededJobs.isEmpty() ? 1 : 2;
            long baseVersion = article.getLockVersion();
            if (articleMapper.purgeRevisionModeLocked(articleId, userId, eventCount,
                    baseVersion, now) != 1) {
                throw optimisticConflict();
            }
            int eventOffset = appendLifecycleSupersededBatch(
                    article, supersededJobs, baseVersion);
            appendLifecycleEvent(article, baseVersion + eventOffset + 1,
                    article.getLifecycleEpoch() + 1, DomainEventType.ARTICLE_DELETED, "PURGED");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void cleanExpiredArticles(LocalDateTime expiredBefore, int limit) {
        requireAnyArticleWriteAllowed();
        for (Long articleId : articleMapper.selectExpiredArticleIds(expiredBefore, limit)) {
            Article article = articleMapper.selectByIdForUpdate(articleId);
            if (article == null || !Integer.valueOf(1).equals(article.getIsDeleted())
                    || "PURGED".equals(article.getVisibilityState())
                    || article.getDeleteTime() == null
                    || article.getDeleteTime().isAfter(expiredBefore)) {
                continue;
            }
            ArticleRevisionMode mode = modeResolver.current();
            if (mode != ArticleRevisionMode.LEGACY) {
                article = ensureCanonicalShadow(article).article();
            }
            LocalDateTime now = LocalDateTime.now();
            if (mode == ArticleRevisionMode.LEGACY) {
                if (articleMapper.purgeExpiredLocked(article.getId(), article.getAuthorId(),
                        article.getLockVersion(), expiredBefore, now) != 1) {
                    throw optimisticConflict();
                }
                rabbitTemplate.convertAndSend("es.sync.queue", articleId);
            } else {
                List<ArticleModerationJob> supersededJobs =
                        supersedeNonTerminalJobs(articleId, now);
                int eventCount = supersededJobs.isEmpty() ? 1 : 2;
                long baseVersion = article.getLockVersion();
                if (articleMapper.purgeExpiredRevisionModeLocked(
                        article.getId(), article.getAuthorId(), eventCount,
                        baseVersion, expiredBefore, now) != 1) {
                    throw optimisticConflict();
                }
                int eventOffset = appendLifecycleSupersededBatch(
                        article, supersededJobs, baseVersion);
                appendLifecycleEvent(article, baseVersion + eventOffset + 1,
                        article.getLifecycleEpoch() + 1,
                        DomainEventType.ARTICLE_DELETED, "EXPIRED_PURGE");
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public long publishOrSave(ArticleDTO dto, boolean publish, long userId) {
        requireAnyArticleWriteAllowed();
        ArticleRevisionMode mode = modeResolver.current();
        if (mode == ArticleRevisionMode.LEGACY) {
            return legacyPublishOrSave(dto, publish, userId);
        }

        boolean newShell = dto.getId() == null;
        Article article = newShell ? createShell(userId) : lockOwnedArticle(dto.getId(), userId);
        if (Integer.valueOf(1).equals(article.getStatus())) {
            mutationGate.requirePublishedRevisionEditAllowed();
        }
        if (publish) {
            checkSensitive(dto.getTitle(), dto.getContent());
        }
        if (mode == ArticleRevisionMode.CUTOVER && !newShell && dto.getExpectedDraftVersion() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DRAFT_VERSION_REQUIRED");
        }
        ArticleDraft saved;
        if (newShell) {
            ArticleDraft currentDraft = lockOrCreateShadowDraft(article);
            long expectedVersion = dto.getExpectedDraftVersion() == null
                    ? currentDraft.getDraftVersion() : dto.getExpectedDraftVersion();
            saved = saveDraftLocked(article, currentDraft, new SaveArticleDraftCommand(
                    article.getId(), expectedVersion, dto.getTitle(), generatedSummary(dto), dto.getContent(),
                    dto.getCover(), dto.getTags()), userId, mode);
        } else {
            long expectedVersion = dto.getExpectedDraftVersion() == null ? -1L : dto.getExpectedDraftVersion();
            saved = saveExistingDraft(article, new SaveArticleDraftCommand(
                    article.getId(), expectedVersion, dto.getTitle(), generatedSummary(dto), dto.getContent(),
                    dto.getCover(), dto.getTags()), userId, mode, dto.getExpectedDraftVersion() == null);
        }
        if (publish) {
            submissionService.submit(new SubmitArticleRevisionCommand(article.getId(), userId,
                    saved.getDraftVersion()));
        }
        return article.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArticleDraft saveDraft(SaveArticleDraftCommand command, long userId) {
        requireRevisionWriteMode();
        if (command.expectedDraftVersion() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DRAFT_VERSION_INVALID");
        }
        boolean newShell = command.articleId() == null;
        Article article = newShell ? createShell(userId) : lockOwnedArticle(command.articleId(), userId);
        ArticleRevisionMode mode = modeResolver.current();
        if (Integer.valueOf(1).equals(article.getStatus())) {
            mutationGate.requirePublishedRevisionEditAllowed();
        }
        ArticleDraft saved;
        if (newShell) {
            ArticleDraft draft = lockOrCreateShadowDraft(article);
            saved = saveDraftLocked(article, draft, command, userId, mode);
        } else {
            saved = saveExistingDraft(article, command, userId, mode, false);
        }
        return saved;
    }

    private ArticleDraft saveExistingDraft(Article article, SaveArticleDraftCommand command,
                                           long userId, ArticleRevisionMode mode,
                                           boolean useCurrentVersionForLegacyCompatibility) {
        ShadowState shadow = ensureCanonicalShadow(article);
        article = shadow.article();
        ArticleDraft draft = shadow.draft();
        SaveArticleDraftCommand effectiveCommand = command;
        if (useCurrentVersionForLegacyCompatibility) {
            effectiveCommand = new SaveArticleDraftCommand(command.articleId(), draft.getDraftVersion(),
                    command.title(), command.summary(), command.bodyMarkdown(), command.cover(), command.tags());
        }
        ArticleDraft saved = saveDraftLocked(article, draft, effectiveCommand, userId, mode);
        return saved;
    }

    private ArticleDraft saveDraftLocked(Article article, ArticleDraft draft,
                                         SaveArticleDraftCommand command, long userId,
                                         ArticleRevisionMode mode) {
        if (draft.getDraftVersion() != command.expectedDraftVersion()) {
            throw optimisticConflict();
        }

        ArticleContentSnapshot snapshot = canonicalizer.canonicalize(command.title(), command.summary(),
                command.bodyMarkdown(), command.cover(), command.tags());
        if (snapshot.tags().size() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ARTICLE_TAG_LIMIT_EXCEEDED");
        }
        LocalDateTime now = LocalDateTime.now();
        if (mode == ArticleRevisionMode.SHADOW) {
            int legacyUpdated;
            if (article.getPendingRevisionId() == null) {
                legacyUpdated = articleMapper.updateLegacyDraftContent(
                        article.getId(), userId, snapshot.title(), snapshot.summary(),
                        snapshot.bodyMarkdown(), snapshot.cover(), article.getLockVersion(), now);
            }
            else {
                legacyUpdated = articleMapper.updateLegacyPendingDraftMirror(
                        article.getId(), userId, article.getPendingRevisionId(),
                        snapshot.title(), snapshot.summary(), snapshot.bodyMarkdown(),
                        snapshot.cover(), article.getLockVersion(), now);
            }
            if (legacyUpdated != 1) {
                throw optimisticConflict();
            }
        }
        int updated = draftMapper.updateOwnerDraftCas(article.getId(), userId,
                command.expectedDraftVersion(), draft.getLockVersion(), snapshot.title(), snapshot.summary(),
                snapshot.bodyMarkdown(), snapshot.bodyPlain(), snapshot.cover(), snapshot.tagsJson(),
                snapshot.contentHash(), now);
        if (updated != 1) {
            throw optimisticConflict();
        }
        ArticleDraft stored = draftMapper.selectOwnerDraftForUpdate(article.getId(), userId);
        assertStoredHash(stored);
        return stored;
    }

    private void requireAnyArticleWriteAllowed() {
        mutationGate.requireArticleWriteAllowed();
    }

    private void requireRevisionWriteMode() {
        mutationGate.requireRevisionWriteMode();
    }

    private long legacyPublishOrSave(ArticleDTO dto, boolean publish, long userId) {
        Article article = dto.getId() == null ? createShell(userId) : lockOwnedArticle(dto.getId(), userId);
        if (Integer.valueOf(1).equals(article.getStatus())) {
            mutationGate.requirePublishedRevisionEditAllowed();
        }
        if (publish) {
            checkSensitive(dto.getTitle(), dto.getContent());
        }
        ArticleContentSnapshot snapshot = canonicalizer.canonicalize(dto.getTitle(), generatedSummary(dto),
                dto.getContent(), dto.getCover(), dto.getTags());
        int status = publish ? 2 : 0;
        int updated = articleMapper.updateLegacyContent(article.getId(), userId, snapshot.title(),
                snapshot.summary(), snapshot.bodyMarkdown(), snapshot.cover(), status,
                article.getLockVersion(), LocalDateTime.now());
        if (updated != 1) {
            throw optimisticConflict();
        }
        legacyTagWriter.replace(article.getId(), snapshot.tags().subList(0, Math.min(5, snapshot.tags().size())));
        rabbitTemplate.convertAndSend(publish ? "article.audit.queue" : "es.sync.queue", article.getId());
        return article.getId();
    }

    private Article createShell(long userId) {
        LocalDateTime now = LocalDateTime.now();
        Article shell = new Article();
        shell.setTitle("");
        shell.setContent(null);
        shell.setSummary(null);
        shell.setCover(null);
        shell.setAuthorId(userId);
        shell.setViewCount(0);
        shell.setLikeCount(0);
        shell.setCommentCount(0);
        shell.setCollectCount(0);
        shell.setCreateTime(now);
        shell.setUpdateTime(now);
        shell.setStatus(0);
        shell.setIsDeleted(0);
        shell.setVisibilityState("PRIVATE");
        shell.setReviewState("NOT_SUBMITTED");
        shell.setLifecycleEpoch(1L);
        shell.setLockVersion(0L);
        if (articleMapper.insert(shell) != 1) {
            throw new IllegalStateException("article shell insert failed");
        }
        Article locked = articleMapper.selectByIdForUpdate(shell.getId());
        if (locked == null) {
            throw new IllegalStateException("article shell disappeared");
        }
        return locked;
    }

    private void checkSensitive(String title, String body) {
        String sensitiveWord = sensitiveUtils.check((title == null ? "" : title)
                + (body == null ? "" : body));
        if (sensitiveWord != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SENSITIVE_CONTENT");
        }
    }

    private static String generatedSummary(ArticleDTO dto) {
        if (StringUtils.hasText(dto.getSummary())) {
            return dto.getSummary();
        }
        String content = dto.getContent() == null ? "" : dto.getContent();
        String clean = content.replaceAll("!\\[.*?]\\(.*?\\)", "")
                .replaceAll("[#*>`~-]", "").trim();
        return clean.length() > 100 ? clean.substring(0, 100) + "..." : clean;
    }

    private Article lockOwnedArticle(long articleId, long userId) {
        Article article = articleMapper.selectByIdForUpdate(articleId);
        if (article == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND");
        }
        if (!Long.valueOf(userId).equals(article.getAuthorId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND");
        }
        if (!Integer.valueOf(0).equals(article.getIsDeleted())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ARTICLE_RECYCLED");
        }
        return article;
    }

    private Article lockArticleForOwnerIncludingDeleted(long articleId, long userId) {
        Article article = articleMapper.selectByIdForUpdate(articleId);
        if (article == null || !Long.valueOf(userId).equals(article.getAuthorId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND");
        }
        return article;
    }

    private void sendLegacyNotification(long administratorId, Article article,
                                        boolean pass, String reason) {
        NotificationMsgDTO notification = new NotificationMsgDTO();
        notification.setFromId(administratorId);
        notification.setToId(article.getAuthorId());
        notification.setType(4);
        notification.setTargetId(article.getId());
        if (pass) {
            notification.setContent("🎉 恭喜！您的文章《" + article.getTitle() + "》已通过人工审核并成功发布。");
        } else {
            String rejectReason = StringUtils.hasText(reason) ? reason : "存在违规内容";
            notification.setContent("⚠️ 抱歉，您的文章《" + article.getTitle()
                    + "》未通过人工审核。原因：" + rejectReason + "。请修改后重新发布。");
        }
        rabbitTemplate.convertAndSend("message.notify.queue", notification);
    }

    private void appendLifecycleEvent(Article article, long version, long lifecycleEpoch,
                                      DomainEventType type, String transition) {
        com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
        payload.put("articleId", article.getId());
        payload.put("transition", transition);
        if (article.getPublishedRevisionId() == null) {
            payload.putNull("publishedRevisionId");
        } else {
            payload.put("publishedRevisionId", article.getPublishedRevisionId());
        }
        outboxService.append("ARTICLE", article.getId(), version, lifecycleEpoch, type, 1,
                payload, eventDedupe(article.getId(), lifecycleEpoch, version, type));
    }

    private com.fasterxml.jackson.databind.JsonNode supersededPayload(
            long articleId, Long replacementRevisionId, List<ArticleModerationJob> jobs) {
        com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
        payload.put("articleId", articleId);
        if (replacementRevisionId == null) {
            payload.putNull("replacementRevisionId");
        } else {
            payload.put("replacementRevisionId", replacementRevisionId);
        }
        com.fasterxml.jackson.databind.node.ArrayNode jobIds = payload.putArray("supersededJobIds");
        com.fasterxml.jackson.databind.node.ArrayNode revisionIds = payload.putArray("supersededRevisionIds");
        com.fasterxml.jackson.databind.node.ArrayNode contentHashes = payload.putArray("supersededContentHashes");
        for (ArticleModerationJob job : jobs) {
            jobIds.add(job.getId());
            revisionIds.add(job.getRevisionId());
            contentHashes.add(job.getContentHash());
        }
        return payload;
    }

    private List<ArticleModerationJob> supersedeNonTerminalJobs(
            long articleId, LocalDateTime updatedAt) {
        List<ArticleModerationJob> jobs = jobMapper.selectNonTerminalForUpdate(articleId).stream()
                .sorted(Comparator.comparingLong(ArticleModerationJob::getId))
                .toList();
        for (ArticleModerationJob job : jobs) {
            if (jobMapper.supersede(job.getId(), job.getLockVersion(), updatedAt) != 1) {
                throw optimisticConflict();
            }
        }
        return jobs;
    }

    private int appendLifecycleSupersededBatch(
            Article article, List<ArticleModerationJob> jobs, long baseVersion) {
        if (jobs.isEmpty()) {
            return 0;
        }
        long supersededVersion = baseVersion + 1;
        outboxService.append("ARTICLE", article.getId(), supersededVersion,
                article.getLifecycleEpoch(), DomainEventType.ARTICLE_REVISION_SUPERSEDED, 1,
                supersededPayload(article.getId(), article.getPublishedRevisionId(), jobs),
                eventDedupe(article, supersededVersion,
                        DomainEventType.ARTICLE_REVISION_SUPERSEDED));
        return 1;
    }

    private static String eventDedupe(Article article, long version, DomainEventType type) {
        return eventDedupe(article.getId(), article.getLifecycleEpoch(), version, type);
    }

    private static String eventDedupe(long articleId, long lifecycleEpoch,
                                      long version, DomainEventType type) {
        return "ARTICLE:" + articleId + ":" + lifecycleEpoch
                + ":" + version + ":" + type.name();
    }

    private ShadowState ensureCanonicalShadow(Article article) {
        ArticleDraft draft = lockOrCreateShadowDraft(article);
        // Lock jobs before inspecting/appending revisions so all mutation paths share one order.
        jobMapper.selectNonTerminalForUpdate(article.getId());
        List<ArticleRevision> existingRevisions = revisionMapper.selectByArticleId(article.getId());
        if (!existingRevisions.isEmpty()) {
            return new ShadowState(article, draft);
        }
        if (Integer.valueOf(0).equals(article.getStatus())
                && "PRIVATE".equals(article.getVisibilityState())
                && "NOT_SUBMITTED".equals(article.getReviewState())
                && article.getLatestRevisionId() == null
                && article.getPendingRevisionId() == null
                && article.getPublishedRevisionId() == null) {
            // A native revision-mode shell has no legacy publication to freeze.
            return new ShadowState(article, draft);
        }

        ArticleContentSnapshot snapshot = canonicalizer.canonicalize(draft.getTitle(), draft.getSummary(),
                draft.getBodyMarkdown(), draft.getCover(), canonicalizerTags(draft.getTagsJson()));
        if (!snapshot.contentHash().equals(draft.getContentHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DRAFT_HASH_MISMATCH");
        }
        LocalDateTime now = LocalDateTime.now();
        ArticleRevision revision = new ArticleRevision();
        revision.setArticleId(article.getId());
        revision.setRevisionNo(1L);
        revision.setTitle(snapshot.title());
        revision.setSummary(snapshot.summary());
        revision.setBodyMarkdown(snapshot.bodyMarkdown());
        revision.setBodyPlain(snapshot.bodyPlain());
        revision.setCover(snapshot.cover());
        revision.setTagsJson(snapshot.tagsJson());
        revision.setContentHash(snapshot.contentHash());
        revision.setSourceDraftVersion(draft.getDraftVersion());
        revision.setCreatedBy(article.getAuthorId());
        revision.setCreatedAt(now);
        if (revisionMapper.insert(revision) != 1) {
            throw new IllegalStateException("shadow revision insert failed");
        }

        Long latest = null;
        Long pending = null;
        Long published = null;
        String visibility;
        String reviewState;
        switch (article.getStatus()) {
            case 0 -> {
                visibility = "PRIVATE";
                reviewState = "NOT_SUBMITTED";
            }
            case 1 -> {
                latest = revision.getId();
                published = revision.getId();
                visibility = "PUBLIC";
                reviewState = "APPROVED";
            }
            case 2 -> {
                latest = revision.getId();
                pending = revision.getId();
                visibility = "PRIVATE";
                reviewState = "AUTO_PENDING";
                ArticleModerationJob job = new ArticleModerationJob();
                job.setArticleId(article.getId());
                job.setRevisionId(revision.getId());
                job.setContentHash(revision.getContentHash());
                job.setState("HUMAN_PENDING");
                job.setAttemptCount(0);
                job.setLastError("LEGACY_SHADOW_MANUAL");
                job.setCreatedAt(now);
                job.setUpdatedAt(now);
                job.setLockVersion(0L);
                if (jobMapper.insert(job) != 1) {
                    throw new IllegalStateException("shadow moderation job insert failed");
                }
            }
            case 3 -> {
                latest = revision.getId();
                visibility = "PRIVATE";
                reviewState = "REJECTED";
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "LEGACY_ARTICLE_STATE_INVALID");
        }
        if (Integer.valueOf(1).equals(article.getIsDeleted())) {
            visibility = "RECYCLED";
        }
        if (articleMapper.initializeShadowState(article.getId(), latest, pending, published,
                visibility, reviewState, article.getLockVersion()) != 1) {
            throw optimisticConflict();
        }
        Article refreshed = articleMapper.selectByIdForUpdate(article.getId());
        return new ShadowState(refreshed, draft);
    }

    private ArticleDraft lockOrCreateShadowDraft(Article article) {
        ArticleDraft existing = draftMapper.selectOwnerDraftForUpdate(article.getId(), article.getAuthorId());
        if (existing != null) {
            return existing;
        }
        ArticleContentSnapshot legacy = canonicalizer.canonicalize(article.getTitle(), article.getSummary(),
                article.getContent(), article.getCover(), loadLegacyTags(article.getId()));
        LocalDateTime now = LocalDateTime.now();
        ArticleDraft created = new ArticleDraft();
        created.setArticleId(article.getId());
        created.setUserId(article.getAuthorId());
        created.setDraftVersion(0L);
        created.setTitle(legacy.title());
        created.setSummary(legacy.summary());
        created.setBodyMarkdown(legacy.bodyMarkdown());
        created.setBodyPlain(legacy.bodyPlain());
        created.setCover(legacy.cover());
        created.setTagsJson(legacy.tagsJson());
        created.setContentHash(legacy.contentHash());
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        created.setLockVersion(0L);
        draftMapper.insert(created);
        return draftMapper.selectOwnerDraftForUpdate(article.getId(), article.getAuthorId());
    }

    private List<String> loadLegacyTags(long articleId) {
        List<ArticleTag> links = articleTagMapper.selectList(
                new QueryWrapper<ArticleTag>().eq("article_id", articleId));
        if (links == null || links.isEmpty()) {
            return List.of();
        }
        List<Long> ids = links.stream().map(ArticleTag::getTagId).toList();
        List<Tag> tags = tagMapper.selectBatchIds(ids);
        if (tags == null) {
            return List.of();
        }
        return tags.stream().map(Tag::getName).toList();
    }

    private void assertStoredHash(ArticleDraft stored) {
        List<String> tags;
        try {
            tags = canonicalizerTags(stored.getTagsJson());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("stored draft tags are invalid", exception);
        }
        ArticleContentSnapshot recomputed = canonicalizer.canonicalize(stored.getTitle(), stored.getSummary(),
                stored.getBodyMarkdown(), stored.getCover(), tags);
        if (!recomputed.contentHash().equals(stored.getContentHash())) {
            throw new IllegalStateException("stored draft hash mismatch");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> canonicalizerTags(String tagsJson) {
        try {
            return objectMapper.readValue(tagsJson, List.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private static ResponseStatusException optimisticConflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT");
    }

    private record ShadowState(Article article, ArticleDraft draft) {
    }
}
