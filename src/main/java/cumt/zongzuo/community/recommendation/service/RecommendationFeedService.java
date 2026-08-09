package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.dto.RecommendationExposureDraft;
import cumt.zongzuo.community.recommendation.dto.RecommendationFeatureSnapshot;
import cumt.zongzuo.community.recommendation.dto.RecommendationFeedResponse;
import cumt.zongzuo.community.recommendation.dto.RecommendationItem;
import cumt.zongzuo.community.recommendation.dto.RecommendationMode;
import cumt.zongzuo.community.recommendation.dto.RecommendationSession;
import cumt.zongzuo.community.recommendation.dto.RecommendationSessionItem;
import cumt.zongzuo.community.recommendation.dto.RecommendationViewRequest;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventType;
import cumt.zongzuo.community.recommendation.entity.RecommendationExposure;
import cumt.zongzuo.community.recommendation.training.RecommendationModel;
import cumt.zongzuo.community.recommendation.training.RecommendationModelLoadResult;
import cumt.zongzuo.community.recommendation.training.RecommendationModelStore;
import cumt.zongzuo.community.service.UserService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class RecommendationFeedService {

    private static final int SESSION_CANDIDATE_LIMIT = 100;
    private static final String CHRONOLOGICAL = "CHRONOLOGICAL";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final RecommendationProperties properties;
    private final RecommendationSessionStore sessionStore;
    private final ArticleMapper articleMapper;
    private final RecommendationCandidateService candidateService;
    private final RecommendationRankingService rankingService;
    private final RecommendationExposureService exposureService;
    private final UserService userService;
    private final RecommendationEventOutboxService outboxService;
    private final RecommendationMetricsService metricsService;
    private final RecommendationEligibilityService eligibilityService;
    private final RecommendationModelStore modelStore;
    private final RecommendationFeedRateLimiter rateLimiter;
    private final Clock clock;

    @Autowired
    public RecommendationFeedService(RecommendationProperties properties,
                                     RecommendationSessionStore sessionStore,
                                     ArticleMapper articleMapper,
                                     RecommendationCandidateService candidateService,
                                     RecommendationRankingService rankingService,
                                     RecommendationExposureService exposureService,
                                     UserService userService,
                                     RecommendationEventOutboxService outboxService,
                                     RecommendationMetricsService metricsService,
                                     RecommendationFeedRateLimiter rateLimiter,
                                     ObjectProvider<Clock> clockProvider,
                                     ObjectProvider<RecommendationEligibilityService> eligibilityProvider,
                                     ObjectProvider<RecommendationModelStore> modelStoreProvider) {
        this(properties, sessionStore, articleMapper, candidateService, rankingService, exposureService,
                userService, outboxService, metricsService,
                clockProvider.getIfAvailable(Clock::systemDefaultZone),
                eligibilityProvider.getIfAvailable(), modelStoreProvider.getIfAvailable(), rateLimiter);
    }

    public RecommendationFeedService(RecommendationProperties properties,
                                     RecommendationSessionStore sessionStore,
                                     ArticleMapper articleMapper,
                                     RecommendationCandidateService candidateService,
                                     RecommendationRankingService rankingService,
                                     RecommendationExposureService exposureService,
                                     UserService userService,
                                     RecommendationEventOutboxService outboxService,
                                     RecommendationMetricsService metricsService,
                                     Clock clock) {
        this(properties, sessionStore, articleMapper, candidateService, rankingService, exposureService, userService,
                outboxService, metricsService, clock, null, null, null);
    }

    public RecommendationFeedService(RecommendationProperties properties,
                                     RecommendationSessionStore sessionStore, ArticleMapper articleMapper,
                                     RecommendationCandidateService candidateService, RecommendationRankingService rankingService,
                                     RecommendationExposureService exposureService,
                                     UserService userService, RecommendationEventOutboxService outboxService,
                                     RecommendationMetricsService metricsService, Clock clock,
                                     RecommendationEligibilityService eligibilityService, RecommendationModelStore modelStore) {
        this(properties, sessionStore, articleMapper, candidateService, rankingService, exposureService, userService,
                outboxService, metricsService, clock, eligibilityService, modelStore, null);
    }

    public RecommendationFeedService(RecommendationProperties properties,
                                     RecommendationSessionStore sessionStore, ArticleMapper articleMapper,
                                     RecommendationCandidateService candidateService, RecommendationRankingService rankingService,
                                     RecommendationExposureService exposureService,
                                     UserService userService, RecommendationEventOutboxService outboxService,
                                     RecommendationMetricsService metricsService, Clock clock,
                                     RecommendationEligibilityService eligibilityService, RecommendationModelStore modelStore,
                                     RecommendationFeedRateLimiter rateLimiter) {
        this.properties = properties;
        this.sessionStore = sessionStore;
        this.articleMapper = articleMapper;
        this.candidateService = candidateService;
        this.rankingService = rankingService;
        this.exposureService = exposureService;
        this.userService = userService;
        this.outboxService = outboxService;
        this.metricsService = metricsService;
        this.clock = clock;
        this.eligibilityService = eligibilityService;
        this.modelStore = modelStore;
        this.rateLimiter = rateLimiter;
    }

    public RecommendationFeedResponse feed(Long userId, String cursor, int requestedSize) {
        int size = Math.clamp(requestedSize, 1, properties.getMaxPageSize());
        if (rateLimiter != null) {
            rateLimiter.checkRequest(userId);
        }
        if (!properties.isEnabled()) {
            return fallback(userId, cursor, size);
        }
        try {
            RecommendationFeedResponse response = cursor == null || cursor.isBlank()
                    ? createSession(userId, size)
                    : pageSession(userId, cursor, size);
            metricsService.recordDeliveries(response);
            return response;
        } catch (InvalidSessionCursorException | RecommendationSessionUnavailableException
                 | RecommendationServingUnavailableException exception) {
            return fallback(userId, cursor, size);
        }
    }

    private RecommendationFeedResponse createSession(Long userId, int size) {
        if (eligibilityService != null && modelStore != null && eligibilityService.isEligible(userId)) {
            RecommendationModelLoadResult loaded = modelStore.loadActive(clock.instant());
            if (loaded.status() == RecommendationModelLoadResult.Status.IO_FAILURE) {
                throw new RecommendationServingUnavailableException("Recommendation model store unavailable");
            }
            if (loaded.model().isPresent()) {
                List<RecommendationCandidate> candidates = candidateService.recallAndAssemble(userId, Set.of());
                List<RecommendationCandidate> ranked;
                try {
                    ranked = candidateService.rankWithModel(userId, candidates, Set.of(),
                            SESSION_CANDIDATE_LIMIT, loaded.model().get());
                } catch (IllegalArgumentException | IllegalStateException modelFailure) {
                    throw new RecommendationServingUnavailableException("Recommendation model inference failed", modelFailure);
                }
                if (ranked.size() >= size) return personalizedSession(userId, size, ranked);
            }
        }
        List<Long> articleIds = articleMapper.selectPublishedChronologicalIds(SESSION_CANDIDATE_LIMIT);
        List<RecommendationSessionItem> items = articleIds.stream()
                .map(articleId -> new RecommendationSessionItem(articleId, null, CHRONOLOGICAL))
                .toList();
        String sessionId = UUID.randomUUID().toString();
        RecommendationSession session = new RecommendationSession(userId, items, RecommendationMode.COLD_START);
        sessionStore.save(sessionId, session);
        return sliceAndExpose(sessionId, userId, session, 0, size);
    }

    private RecommendationFeedResponse personalizedSession(Long userId, int size,
                                                            List<RecommendationCandidate> candidates) {
        List<RecommendationSessionItem> items = candidates.stream().map(candidate -> {
            RecommendationFeatureSnapshot snapshot = new RecommendationFeatureSnapshot(
                    candidate.tagAffinity(), candidate.authorAffinity(), candidate.similarScore(), candidate.heatScore(),
                    candidate.freshnessScore(), candidate.sources().contains(RecommendationCandidate.Source.FOLLOW) ? 1D : 0D,
                    candidate.sources().contains(RecommendationCandidate.Source.TAG) ? 1D : 0D,
                    candidate.sources().contains(RecommendationCandidate.Source.SIMILAR) ? 1D : 0D,
                    candidate.sources().contains(RecommendationCandidate.Source.EXPLORE) ? 1D : 0D);
            return new RecommendationSessionItem(candidate.articleId(), candidate.reason(),
                    rankingService.winningSource(candidate), snapshot,
                    rankingService.ruleScore(candidate));
        }).toList();
        String sessionId = UUID.randomUUID().toString();
        RecommendationSession session = new RecommendationSession(userId, items, RecommendationMode.PERSONALIZED);
        sessionStore.save(sessionId, session);
        return sliceAndExpose(sessionId, userId, session, 0, size);
    }

    private RecommendationFeedResponse pageSession(Long userId, String cursor, int size) {
        SessionCursor decoded = decodeSessionCursor(cursor);
        RecommendationSession session = sessionStore.load(decoded.sessionId());
        if (session == null || !userId.equals(session.userId())) {
            throw new InvalidSessionCursorException();
        }
        return sliceAndExpose(decoded.sessionId(), userId, session, decoded.offset(), size);
    }

    private RecommendationFeedResponse sliceAndExpose(String sessionId, Long userId,
                                                       RecommendationSession session, int offset, int size) {
        if (offset < 0 || offset > session.items().size()) {
            throw new InvalidSessionCursorException();
        }
        int end = Math.min(session.items().size(), offset + size);
        List<RecommendationSessionItem> pageItems = session.items().subList(offset, end);
        List<Long> pageIds = pageItems.stream().map(RecommendationSessionItem::articleId).toList();
        Map<Long, Article> articlesById = new LinkedHashMap<>();
        if (!pageIds.isEmpty()) {
            for (Article article : articleMapper.selectPublishedByIds(pageIds)) {
                articlesById.put(article.getId(), article);
            }
        }
        List<HydratedRecommendationItem> hydrated = pageItems.stream()
                .filter(item -> articlesById.containsKey(item.articleId()))
                .map(item -> hydrate(item, articlesById.get(item.articleId())))
                .toList();
        enrichAuthors(hydrated.stream().map(item -> item.item().article()).toList());
        List<RecommendationItem> exposed = exposePage(sessionId, userId, hydrated);
        String nextCursor = end < session.items().size()
                ? encode(sessionId + ":" + end)
                : null;
        return new RecommendationFeedResponse(exposed, nextCursor, session.mode());
    }

    private RecommendationFeedResponse fallback(Long userId, String cursor, int size) {
        FallbackCursor position = decodeFallbackCursor(cursor, userId);
        String visitNonce = position == null ? UUID.randomUUID().toString() : position.visitNonce();
        List<Article> articles = articleMapper.selectPublishedChronological(
                position == null ? null : position.createTime(),
                position == null ? null : position.articleId(), size);
        enrichAuthors(articles);
        String pageSessionId = fallbackPageSessionId(userId, visitNonce, position);
        List<HydratedRecommendationItem> hydrated = articles.stream()
                .map(article -> chronology(new RecommendationItem(article, null, CHRONOLOGICAL, null)))
                .toList();
        List<RecommendationItem> exposed = exposePage(pageSessionId, userId, hydrated);
        String nextCursor = null;
        if (articles.size() == size && !articles.isEmpty()) {
            Article last = articles.getLast();
            nextCursor = encode("fallback|" + userId + "|" + visitNonce + "|"
                    + last.getCreateTime() + "|" + last.getId());
        }
        return new RecommendationFeedResponse(exposed, nextCursor, RecommendationMode.FALLBACK);
    }

    private List<RecommendationItem> exposePage(String sessionId, Long userId,
                                                List<HydratedRecommendationItem> items) {
        List<RecommendationExposureDraft> drafts = items.stream()
                .map(item -> new RecommendationExposureDraft(
                        item.item().article().getId(), item.item().source(), item.snapshot(), item.baselineScore()))
                .toList();
        List<Long> exposureIds = exposureService.recordPage(sessionId, userId, drafts);
        return java.util.stream.IntStream.range(0, items.size())
                .mapToObj(index -> items.get(index).item().withExposureId(exposureIds.get(index)))
                .toList();
    }

    private HydratedRecommendationItem hydrate(RecommendationSessionItem sessionItem, Article article) {
        RecommendationItem item = new RecommendationItem(article, sessionItem.reason(), sessionItem.source(), null);
        if (sessionItem.snapshot() == null) {
            return chronology(item);
        }
        return new HydratedRecommendationItem(
                item, sessionItem.snapshot(), sessionItem.baselineScore());
    }

    private HydratedRecommendationItem chronology(RecommendationItem item) {
        Article article = item.article();
        RecommendationCandidate candidate = candidateService.assembleChronologicalFeatures(article);
        RecommendationFeatureSnapshot snapshot = new RecommendationFeatureSnapshot(
                candidate.tagAffinity(), candidate.authorAffinity(), candidate.similarScore(),
                candidate.heatScore(), candidate.freshnessScore(), 0D, 0D, 0D, 0D);
        return new HydratedRecommendationItem(item, snapshot, rankingService.ruleScore(candidate));
    }

    private void enrichAuthors(List<Article> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }
        Set<Long> authorIds = articles.stream().map(Article::getAuthorId).collect(java.util.stream.Collectors.toSet());
        Map<Long, User> authors;
        try {
            authors = userService.getUserMapCached(authorIds);
        } catch (DataAccessException redisFailure) {
            authors = userService.listByIds(authorIds).stream()
                    .collect(java.util.stream.Collectors.toMap(User::getId, user -> user));
        }
        for (Article article : articles) {
            User author = authors == null ? null : authors.get(article.getAuthorId());
            if (author == null) {
                article.setAuthorName("注销用户");
                article.setAuthorAvatar(null);
            } else {
                article.setAuthorName(author.getUsername());
                article.setAuthorAvatar(author.getAvatar());
            }
        }
    }

    public void recordView(Long userId, Long articleId, RecommendationViewRequest request) {
        Article article = articleMapper.selectPublicById(articleId);
        if (article == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文章不存在或未发布");
        }
        Long exposureId = request == null ? null : request.exposureId();
        if (exposureId != null) {
            RecommendationExposure exposure = exposureService.get(exposureId);
            if (exposure == null || !userId.equals(exposure.getUserId())
                    || !articleId.equals(exposure.getArticleId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "曝光记录与当前阅读不匹配");
            }
        }
        String dedupeKey = "view:" + userId + ":article:" + articleId + ":"
                + LocalDate.now(clock.withZone(SHANGHAI));
        String source = exposureId == null ? "article_detail" : "recommendation:" + exposureId;
        outboxService.enqueueViewIfAbsent(new RecommendationEventCommand(
                userId, articleId, article.getAuthorId(), RecommendationEventType.VIEW,
                LocalDateTime.now(clock).withNano(0), dedupeKey, source));
    }

    private SessionCursor decodeSessionCursor(String cursor) {
        try {
            String decoded = decode(cursor);
            if (decoded.length() > 128) {
                throw new InvalidSessionCursorException();
            }
            int separator = decoded.lastIndexOf(':');
            if (separator <= 0) {
                throw new InvalidSessionCursorException();
            }
            String sessionId = decoded.substring(0, separator);
            UUID.fromString(sessionId);
            return new SessionCursor(sessionId, Integer.parseInt(decoded.substring(separator + 1)));
        } catch (IllegalArgumentException exception) {
            throw new InvalidSessionCursorException();
        }
    }

    private FallbackCursor decodeFallbackCursor(String cursor, Long userId) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = decode(cursor);
            if (decoded.length() > 256) {
                return null;
            }
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 5 || !"fallback".equals(parts[0])
                    || !userId.equals(Long.valueOf(parts[1]))) {
                return null;
            }
            String visitNonce = UUID.fromString(parts[2]).toString();
            return new FallbackCursor(visitNonce, LocalDateTime.parse(parts[3]), Long.valueOf(parts[4]));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String fallbackPageSessionId(Long userId, String visitNonce, FallbackCursor position) {
        if (position == null) {
            return visitNonce;
        }
        String pageIdentity = "fallback:" + userId + ":" + visitNonce + ":"
                + position.createTime() + ":" + position.articleId();
        return UUID.nameUUIDFromBytes(pageIdentity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String decode(String value) {
        if (value == null || value.length() > 512) {
            throw new IllegalArgumentException("Cursor is too long");
        }
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private record SessionCursor(String sessionId, int offset) {
    }

    private record FallbackCursor(String visitNonce, LocalDateTime createTime, Long articleId) {
    }

    private record HydratedRecommendationItem(
            RecommendationItem item,
            RecommendationFeatureSnapshot snapshot,
            Double baselineScore) {
    }

    private static class InvalidSessionCursorException extends RuntimeException {
    }
}
