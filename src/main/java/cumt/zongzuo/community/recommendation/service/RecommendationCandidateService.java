package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.document.ArticleDoc;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.ArticleTagMapper;
import cumt.zongzuo.community.mapper.TagMapper;
import cumt.zongzuo.community.recommendation.mapper.UserArticleEventMapper;
import cumt.zongzuo.community.recommendation.service.RecommendationCandidate.Source;
import cumt.zongzuo.community.recommendation.training.RecommendationModel;
import cumt.zongzuo.community.article.service.PublishedArticleReadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RecommendationCandidateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecommendationCandidateService.class);
    static final int FOLLOW_LIMIT = 20;
    static final int TAG_LIMIT = 40;
    static final int SIMILAR_LIMIT = 30;
    static final int EXPLORE_LIMIT = 20;
    static final int TOTAL_LIMIT = 100;
    private static final int PROFILE_TAG_LIMIT = 40;
    private static final int PROFILE_AUTHOR_LIMIT = 100;
    private static final int SIMILAR_SEED_LIMIT = 5;
    private static final int READ_WINDOW_DAYS = 30;
    private static final int MAX_SIMILAR_SEARCH_LIMIT = SIMILAR_LIMIT * 2;

    private final ArticleMapper articleMapper;
    private final ArticleTagMapper articleTagMapper;
    private final TagMapper tagMapper;
    private final UserArticleEventMapper eventMapper;
    private final RecommendationProfileService profileService;
    private final ElasticsearchOperations elasticsearchOperations;
    private final RecommendationRankingService rankingService;
    private final Clock clock;
    private final PublishedArticleReadService publishedReads;

    @Autowired
    public RecommendationCandidateService(ArticleMapper articleMapper,
                                          ArticleTagMapper articleTagMapper,
                                          TagMapper tagMapper,
                                          UserArticleEventMapper eventMapper,
                                          RecommendationProfileService profileService,
                                          ElasticsearchOperations elasticsearchOperations,
                                          RecommendationRankingService rankingService,
                                          org.springframework.beans.factory.ObjectProvider<Clock> clockProvider,
                                          PublishedArticleReadService publishedReads) {
        this(articleMapper, articleTagMapper, tagMapper, eventMapper, profileService,
                elasticsearchOperations, rankingService,
                clockProvider.getIfAvailable(Clock::systemDefaultZone), publishedReads);
    }

    public RecommendationCandidateService(ArticleMapper articleMapper,
                                          ArticleTagMapper articleTagMapper,
                                          TagMapper tagMapper,
                                          UserArticleEventMapper eventMapper,
                                          RecommendationProfileService profileService,
                                          ElasticsearchOperations elasticsearchOperations,
                                          RecommendationRankingService rankingService,
                                          Clock clock) {
        this(articleMapper, articleTagMapper, tagMapper, eventMapper, profileService,
                elasticsearchOperations, rankingService, clock, null);
    }

    private RecommendationCandidateService(ArticleMapper articleMapper,
                                           ArticleTagMapper articleTagMapper,
                                           TagMapper tagMapper,
                                           UserArticleEventMapper eventMapper,
                                           RecommendationProfileService profileService,
                                           ElasticsearchOperations elasticsearchOperations,
                                           RecommendationRankingService rankingService,
                                           Clock clock,
                                           PublishedArticleReadService publishedReads) {
        this.articleMapper = articleMapper;
        this.articleTagMapper = articleTagMapper;
        this.tagMapper = tagMapper;
        this.eventMapper = eventMapper;
        this.profileService = profileService;
        this.elasticsearchOperations = elasticsearchOperations;
        this.rankingService = rankingService;
        this.clock = clock;
        this.publishedReads = publishedReads;
    }

    public List<RecommendationCandidate> recallAndRank(Long userId, Set<Long> shownArticleIds, int limit) {
        List<RecommendationCandidate> featured = recallAndAssemble(userId, shownArticleIds);
        if (featured.isEmpty() || limit <= 0) return List.of();
        return rankingService.diversify(rankingService.rank(userId, featured,
                normalizedArticleIds(shownArticleIds)), limit);
    }

    public List<RecommendationCandidate> recallAndAssemble(Long userId, Set<Long> shownArticleIds) {
        if (userId == null) {
            return List.of();
        }
        Set<Long> shown = normalizedArticleIds(shownArticleIds);
        Map<String, Double> tagProfile;
        Map<Long, Double> authorProfile;
        tagProfile = safeMap(profileService.profileTags(userId, PROFILE_TAG_LIMIT));
        authorProfile = safeMap(profileService.profileAuthors(userId, PROFILE_AUTHOR_LIMIT));

        List<Article> follow = publishedReads == null
                ? safeList(articleMapper.selectLegacyPublishedByFollowedAuthors(
                        userId, userId, shown, FOLLOW_LIMIT))
                : publishedReads.findFollowedCandidates(userId, userId, shown, FOLLOW_LIMIT);
        List<Article> tag = recallByTags(tagProfile.keySet(), userId, shown);
        List<Article> similar = recallSimilar(userId, shown);
        List<Article> explore = publishedReads == null
                ? safeList(articleMapper.selectLegacyPublishedHotFresh(userId, shown, EXPLORE_LIMIT))
                : publishedReads.findHotFreshCandidates(userId, shown, EXPLORE_LIMIT);
        List<RecommendationCandidate> recalled = mergeRecallSources(follow, tag, similar, explore);

        LocalDateTime cutoff = LocalDateTime.now(clock).withNano(0).minusDays(READ_WINDOW_DAYS);
        Set<Long> recentlyInteracted = new HashSet<>(safeList(
                eventMapper.selectRecentlyInteractedArticleIds(userId, cutoff)));
        return recalled.stream()
                .map(candidate -> assembleFeatures(candidate, tagProfile, authorProfile, recentlyInteracted))
                .toList();
    }

    public List<RecommendationCandidate> rankWithModel(Long userId, List<RecommendationCandidate> candidates,
                                                       Set<Long> shownArticleIds, int limit, RecommendationModel model) {
        return rankingService.diversify(rankingService.rankWithModel(userId, candidates,
                normalizedArticleIds(shownArticleIds), model), limit);
    }

    public RecommendationCandidate assembleFeatures(Long userId, Article article) {
        if (userId == null || article == null) {
            throw new IllegalArgumentException("userId and article must not be null");
        }
        Map<String, Double> tagProfile = safeMap(profileService.profileTags(userId, PROFILE_TAG_LIMIT));
        Map<Long, Double> authorProfile = safeMap(profileService.profileAuthors(userId, PROFILE_AUTHOR_LIMIT));
        LocalDateTime cutoff = LocalDateTime.now(clock).withNano(0).minusDays(READ_WINDOW_DAYS);
        Set<Long> recentlyInteracted = new HashSet<>(safeList(
                eventMapper.selectRecentlyInteractedArticleIds(userId, cutoff)));
        RecommendationCandidate candidate = RecommendationCandidate.unranked(
                article, Set.of(), Set.of(), 0D, 0D, 0D, 0D, 0D, 0D);
        return assembleFeatures(candidate, tagProfile, authorProfile, recentlyInteracted);
    }

    /**
     * Builds the feature snapshot for chronological delivery without consulting
     * user profile Redis. Cold-start and fallback must remain available when the
     * recommendation data plane is unavailable.
     */
    public RecommendationCandidate assembleChronologicalFeatures(Article article) {
        if (article == null) {
            throw new IllegalArgumentException("article must not be null");
        }
        return RecommendationCandidate.unranked(article, Set.of(), Set.of(),
                0D, 0D, 0D,
                RecommendationRankingService.normalizeHeat(rawHeat(article)),
                freshness(article.getCreateTime()), 0D);
    }

    public List<Article> recallSimilar(Long userId) {
        return recallSimilar(userId, Set.of());
    }

    public List<Article> recallSimilar(Long userId, Set<Long> shownArticleIds) {
        LocalDateTime cutoff = LocalDateTime.now(clock).withNano(0).minusDays(READ_WINDOW_DAYS);
        List<Long> seedIds = safeList(eventMapper.selectRecentSeedArticleIds(
                userId, cutoff, SIMILAR_SEED_LIMIT)).stream().limit(SIMILAR_SEED_LIMIT).toList();
        if (seedIds.isEmpty()) {
            return List.of();
        }
        Set<Long> excludedArticleIds = new LinkedHashSet<>(seedIds);
        excludedArticleIds.addAll(normalizedArticleIds(shownArticleIds));
        String likeDocuments = seedIds.stream()
                .map(id -> "{\"_index\":\"article\",\"_id\":\"" + id + "\"}")
                .collect(Collectors.joining(","));
        String mltQueryJson = "{\"more_like_this\":{"
                + "\"fields\":[\"title\",\"summary\",\"content\"],"
                + "\"like\":[" + likeDocuments + "],"
                + "\"min_term_freq\":1,\"max_query_terms\":25}}";
        String excludedIdsJson = excludedArticleIds.stream().map(String::valueOf)
                .collect(Collectors.joining(","));
        String queryJson = "{\"bool\":{\"must\":[" + mltQueryJson + "],\"must_not\":["
                + "{\"ids\":{\"values\":[" + excludedIdsJson + "]}},"
                + "{\"term\":{\"authorId\":" + userId + "}},"
                + "{\"term\":{\"projectionTombstone\":true}}]}}";
        StringQuery query = new StringQuery(queryJson);
        query.setPageable(PageRequest.of(0, similarSearchLimit(excludedArticleIds.size() + 1)));
        SearchHits<ArticleDoc> hits;
        try {
            hits = elasticsearchOperations.search(query, ArticleDoc.class);
        } catch (RuntimeException elasticsearchFailure) {
            LOGGER.warn("Elasticsearch similarity recall unavailable; continuing without SIMILAR candidates");
            return List.of();
        }
        List<Long> hitIds = hits.stream()
                .map(SearchHit::getContent)
                .filter(Objects::nonNull)
                .filter(document -> document.getId() != null
                        && !excludedArticleIds.contains(document.getId())
                        && !userId.equals(document.getAuthorId()))
                .map(ArticleDoc::getId)
                .distinct()
                .limit(query.getPageable().getPageSize())
                .toList();
        if (hitIds.isEmpty()) {
            return List.of();
        }
        List<Article> authorized = publishedReads == null
                ? safeList(articleMapper.selectLegacyPublishedByIds(hitIds))
                : publishedReads.findByIds(hitIds);
        Map<Long, Article> publishedById = authorized.stream()
                .collect(Collectors.toMap(Article::getId, article -> article, (first, ignored) -> first));
        return hitIds.stream().map(publishedById::get)
                .filter(Objects::nonNull)
                .limit(SIMILAR_LIMIT)
                .toList();
    }

    public static List<RecommendationCandidate> mergeRecallSources(List<Article> follow,
                                                                    List<Article> tag,
                                                                    List<Article> similar,
                                                                    List<Article> explore) {
        Map<Source, List<Article>> sources = new EnumMap<>(Source.class);
        sources.put(Source.FOLLOW, safeList(follow).stream().limit(FOLLOW_LIMIT).toList());
        sources.put(Source.TAG, safeList(tag).stream().limit(TAG_LIMIT).toList());
        sources.put(Source.SIMILAR, safeList(similar).stream().limit(SIMILAR_LIMIT).toList());
        sources.put(Source.EXPLORE, safeList(explore).stream().limit(EXPLORE_LIMIT).toList());

        LinkedHashMap<Long, RecommendationCandidate> merged = new LinkedHashMap<>();
        for (Source source : Source.values()) {
            for (Article article : sources.getOrDefault(source, List.of())) {
                if (article == null || article.getId() == null) {
                    continue;
                }
                RecommendationCandidate existing = merged.get(article.getId());
                if (existing != null) {
                    LinkedHashSet<Source> allSources = new LinkedHashSet<>(existing.sources());
                    allSources.add(source);
                    merged.put(article.getId(), existing.withSources(allSources));
                } else if (merged.size() < TOTAL_LIMIT) {
                    merged.put(article.getId(), RecommendationCandidate.unranked(
                            article, Set.of(source), Set.of(), 0D, 0D, 0D, 0D, 0D, 0D));
                }
            }
        }
        return List.copyOf(merged.values());
    }

    private List<Article> recallByTags(Collection<String> tagNames, Long excludedAuthorId,
                                       Set<Long> shownArticleIds) {
        if (tagNames.isEmpty()) {
            return List.of();
        }
        List<Long> tagIds = publishedReads != null && publishedReads.pointerReadsEnabled()
                ? List.of()
                : safeList(tagMapper.selectIdsByNames(tagNames));
        if (publishedReads != null) {
            return publishedReads.findTagCandidates(tagNames, tagIds, excludedAuthorId,
                    shownArticleIds, TAG_LIMIT);
        }
        if (tagIds.isEmpty()) return List.of();
        return safeList(articleMapper.selectLegacyPublishedByTagIds(
                tagIds, excludedAuthorId, shownArticleIds, TAG_LIMIT));
    }

    private RecommendationCandidate assembleFeatures(RecommendationCandidate candidate,
                                                       Map<String, Double> tagProfile,
                                                       Map<Long, Double> authorProfile,
                                                       Set<Long> recentlyInteracted) {
        List<String> articleTags = candidate.article().getTagList() != null
                ? candidate.article().getTagList()
                : publishedReads != null && publishedReads.pointerReadsEnabled()
                        ? List.of()
                        : safeList(articleTagMapper.selectTagNamesByArticleId(candidate.articleId()));
        LinkedHashSet<String> orderedTags = articleTags.stream()
                .distinct()
                .sorted((left, right) -> {
                    int affinityOrder = Double.compare(
                            tagProfile.getOrDefault(right, 0D), tagProfile.getOrDefault(left, 0D));
                    return affinityOrder != 0 ? affinityOrder : left.compareTo(right);
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
        double topTagScore = topScore(tagProfile.values());
        double tagAffinity = orderedTags.stream()
                .mapToDouble(tag -> normalizedProfileScore(tagProfile.get(tag), topTagScore))
                .max().orElse(0D);
        double topAuthorScore = topScore(authorProfile.values());
        double authorAffinity = normalizedProfileScore(
                authorProfile.get(candidate.authorId()), topAuthorScore);
        double similarScore = candidate.sources().contains(Source.SIMILAR) ? 1D : 0D;
        double heatScore = RecommendationRankingService.normalizeHeat(rawHeat(candidate.article()));
        double freshnessScore = freshness(candidate.article().getCreateTime());
        double readPenalty = recentlyInteracted.contains(candidate.articleId()) ? 2D : 0D;
        return candidate.withFeatures(orderedTags, tagAffinity, authorAffinity, similarScore,
                heatScore, freshnessScore, readPenalty);
    }

    private double freshness(LocalDateTime createdAt) {
        if (createdAt == null) {
            return 0D;
        }
        long ageMinutes = Math.max(0L, Duration.between(createdAt, LocalDateTime.now(clock)).toMinutes());
        return Math.max(0D, Math.min(1D, 1D - ageMinutes / (7D * 24D * 60D)));
    }

    private static long rawHeat(Article article) {
        return nonNegative(article.getViewCount())
                + nonNegative(article.getLikeCount()) * 3L
                + nonNegative(article.getCollectCount()) * 5L
                + nonNegative(article.getCommentCount()) * 4L;
    }

    private static long nonNegative(Integer value) {
        return value == null ? 0L : Math.max(0, value);
    }

    private static double topScore(Collection<Double> scores) {
        return scores.stream().filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue)
                .filter(score -> score > 0D).max().orElse(0D);
    }

    private static double normalizedProfileScore(Double score, double topScore) {
        if (score == null || score <= 0D || topScore <= 0D) {
            return 0D;
        }
        return Math.min(1D, score / topScore);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <K> Map<K, Double> safeMap(Map<K, Double> values) {
        return values == null ? Map.of() : values;
    }

    private static Set<Long> normalizedArticleIds(Collection<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Set.of();
        }
        return articleIds.stream().filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static int similarSearchLimit(int knownExclusionCount) {
        return Math.min(MAX_SIMILAR_SEARCH_LIMIT,
                SIMILAR_LIMIT + Math.max(0, knownExclusionCount));
    }

}
