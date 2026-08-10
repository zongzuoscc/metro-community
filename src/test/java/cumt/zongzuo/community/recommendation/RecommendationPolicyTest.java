package cumt.zongzuo.community.recommendation;

import cumt.zongzuo.community.document.ArticleDoc;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.ArticleTagMapper;
import cumt.zongzuo.community.mapper.TagMapper;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventType;
import cumt.zongzuo.community.recommendation.mapper.UserArticleEventMapper;
import cumt.zongzuo.community.recommendation.service.RecommendationCandidate;
import cumt.zongzuo.community.recommendation.service.RecommendationCandidate.Source;
import cumt.zongzuo.community.recommendation.service.RecommendationCandidateService;
import cumt.zongzuo.community.recommendation.service.RecommendationProfileService;
import cumt.zongzuo.community.recommendation.service.RecommendationRankingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.query.StringQuery;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationPolicyTest {

    private static final Long USER_ID = 7L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-09T04:00:00Z"), ZoneOffset.UTC);
    private final RecommendationRankingService rankingService = new RecommendationRankingService();

    @Test
    void eventTypesExposeTheApprovedInterestWeights() {
        assertThat(RecommendationEventType.VIEW.weight()).isEqualTo(1);
        assertThat(RecommendationEventType.LIKE.weight()).isEqualTo(4);
        assertThat(RecommendationEventType.COLLECT.weight()).isEqualTo(8);
        assertThat(RecommendationEventType.COMMENT.weight()).isEqualTo(6);
        assertThat(RecommendationEventType.FOLLOW_AUTHOR.weight()).isEqualTo(10);
    }

    @Test
    void rankUsesTheApprovedWeightsAndKeepsRecentlyReadArticlesWithPenalty() {
        RecommendationCandidate unread = candidate(article(1, 20, 1, 0), Set.of(Source.TAG), Set.of("Java"),
                0.8, 0.6, 0.0, 0.5, 0.4, 0.0);
        RecommendationCandidate recentlyRead = candidate(article(2, 21, 1, 0), Set.of(Source.SIMILAR), Set.of("ES"),
                0.8, 0.6, 1.0, 0.5, 0.4, 2.0);

        List<RecommendationCandidate> ranked = rankingService.rank(USER_ID, List.of(recentlyRead, unread), Set.of());

        assertThat(ranked).extracting(RecommendationCandidate::articleId).containsExactly(1L, 2L);
        assertThat(ranked.getFirst().score()).isCloseTo(4.8, within(0.000_001));
        assertThat(ranked.get(1).score()).isCloseTo(4.8, within(0.000_001));
        assertThat(ranked.get(1).readPenalty()).isEqualTo(2.0);
    }

    @Test
    void rankExcludesSelfShownAndInvisibleArticlesButNotHistoricalReads() {
        RecommendationCandidate eligible = candidate(article(1, 20, 1, 0), Set.of(Source.TAG), Set.of("Java"),
                1.0, 1.0, 0.0, 0.0, 0.0, 0.0);
        RecommendationCandidate historicalRead = candidate(article(2, 21, 1, 0), Set.of(Source.EXPLORE), Set.of("DB"),
                0.0, 0.0, 0.0, 0.0, 0.0, 2.0);
        RecommendationCandidate self = candidate(article(3, USER_ID, 1, 0), Set.of(Source.FOLLOW), Set.of(),
                1.0, 1.0, 0.0, 0.0, 0.0, 0.0);
        RecommendationCandidate draft = candidate(article(4, 22, 0, 0), Set.of(Source.EXPLORE), Set.of(),
                0.0, 0.0, 0.0, 1.0, 1.0, 0.0);
        RecommendationCandidate deleted = candidate(article(5, 23, 1, 1), Set.of(Source.EXPLORE), Set.of(),
                0.0, 0.0, 0.0, 1.0, 1.0, 0.0);
        RecommendationCandidate shown = candidate(article(6, 24, 1, 0), Set.of(Source.EXPLORE), Set.of(),
                0.0, 0.0, 0.0, 1.0, 1.0, 0.0);

        List<RecommendationCandidate> ranked = rankingService.rank(
                USER_ID, List.of(self, historicalRead, deleted, shown, eligible, draft), Set.of(6L));

        assertThat(ranked).extracting(RecommendationCandidate::articleId)
                .containsExactly(1L, 2L)
                .doesNotContain(3L, 4L, 5L, 6L);
    }

    @Test
    void reasonNamesOnlyARealWinningSource() {
        RecommendationCandidate tag = candidate(article(1, 20, 1, 0), Set.of(Source.TAG),
                linkedSet("Redis"), 0.9, 0.0, 0.0, 0.0, 0.0, 0.0);
        RecommendationCandidate similar = candidate(article(2, 21, 1, 0), Set.of(Source.SIMILAR, Source.EXPLORE),
                Set.of("Java"), 0.0, 0.0, 1.0, 0.0, 0.0, 0.0);
        RecommendationCandidate follow = candidate(article(3, 22, 1, 0), Set.of(Source.FOLLOW),
                Set.of(), 0.0, 0.7, 0.0, 0.0, 0.0, 0.0);
        RecommendationCandidate explore = candidate(article(4, 23, 1, 0), Set.of(Source.EXPLORE),
                Set.of(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

        Map<Long, String> reasons = rankingService.rank(USER_ID, List.of(tag, similar, follow, explore), Set.of())
                .stream().collect(java.util.stream.Collectors.toMap(
                        RecommendationCandidate::articleId, RecommendationCandidate::reason));

        assertThat(reasons).containsEntry(1L, "因为你常看 Redis")
                .containsEntry(2L, "与你最近阅读的内容相似")
                .containsEntry(3L, "来自你关注的作者")
                .containsEntry(4L, "社区近期热议");
    }

    @Test
    void diversityAllowsAtMostTwoConsecutiveAuthorsAndFourTopTenSameTags() {
        List<RecommendationCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            candidates.add(candidate(article(index + 1L, 99L, 1, 0), Set.of(Source.TAG), Set.of("same"),
                    1.0 - index * 0.01, 0.0, 0.0, 0.0, 0.0, 0.0));
        }
        for (int index = 6; index < 16; index++) {
            candidates.add(candidate(article(index + 1L, 100L + index, 1, 0), Set.of(Source.EXPLORE),
                    Set.of("tag-" + index), 1.0 - index * 0.01, 0.0, 0.0, 0.0, 0.0, 0.0));
        }
        List<RecommendationCandidate> scored = rankingService.rank(USER_ID, candidates, Set.of());

        List<RecommendationCandidate> diversified = rankingService.diversify(scored, 10);

        assertThat(diversified).hasSize(10);
        assertThat(maxConsecutiveAuthorCount(diversified)).isLessThanOrEqualTo(2);
        assertThat(maxTagFrequency(diversified)).isLessThanOrEqualTo(4);
    }

    @Test
    void diversityBackfillsSkippedCandidatesWhenOtherwiseShort() {
        List<RecommendationCandidate> candidates = IntStream.range(0, 5)
                .mapToObj(index -> candidate(article(index + 1L, 99L, 1, 0), Set.of(Source.EXPLORE), Set.of("same"),
                        1.0 - index * 0.01, 0.0, 0.0, 0.0, 0.0, 0.0))
                .toList();

        List<RecommendationCandidate> diversified = rankingService.diversify(
                rankingService.rank(USER_ID, candidates, Set.of()), 5);

        assertThat(diversified).hasSize(5);
        assertThat(diversified).extracting(RecommendationCandidate::articleId)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void recallCapsEverySourceBeforeDeduplicationAndPreservesAllNominatingSources() {
        List<Article> follow = articles(1, 30);
        List<Article> tag = articles(11, 50);
        List<Article> similar = articles(61, 40);
        List<Article> explore = articles(91, 30);

        List<RecommendationCandidate> candidates = RecommendationCandidateService.mergeRecallSources(
                follow, tag, similar, explore);

        assertThat(candidates).hasSize(100);
        assertThat(candidates).extracting(RecommendationCandidate::articleId).doesNotHaveDuplicates();
        assertThat(sourceCount(candidates, Source.FOLLOW)).isEqualTo(20);
        assertThat(sourceCount(candidates, Source.TAG)).isEqualTo(40);
        assertThat(sourceCount(candidates, Source.SIMILAR)).isEqualTo(30);
        assertThat(sourceCount(candidates, Source.EXPLORE)).isEqualTo(20);
        assertThat(candidates).filteredOn(candidate -> candidate.articleId().equals(11L))
                .singleElement().extracting(RecommendationCandidate::sources)
                .isEqualTo(Set.of(Source.FOLLOW, Source.TAG));
    }

    @Test
    void heatNormalizationIsReusableAndSaturates() {
        assertThat(RecommendationRankingService.normalizeHeat(0)).isZero();
        assertThat(RecommendationRankingService.normalizeHeat(1_000)).isEqualTo(0.5);
        assertThat(RecommendationRankingService.normalizeHeat(Long.MAX_VALUE)).isBetween(0.999999, 1.0);
        assertThat(RecommendationRankingService.normalizeHeat(-5)).isZero();
    }

    @Test
    void assembleFeaturesUsesTheSameProfileHeatFreshnessAndReadDefinitions() {
        ArticleMapper articleMapper = mock(ArticleMapper.class);
        ArticleTagMapper articleTagMapper = mock(ArticleTagMapper.class);
        TagMapper tagMapper = mock(TagMapper.class);
        UserArticleEventMapper eventMapper = mock(UserArticleEventMapper.class);
        RecommendationProfileService profileService = mock(RecommendationProfileService.class);
        ElasticsearchOperations elasticsearchOperations = mock(ElasticsearchOperations.class);
        Article article = article(1, 20, 1, 0);
        article.setCreateTime(LocalDateTime.now(CLOCK));
        article.setViewCount(100);
        article.setLikeCount(100);
        article.setCollectCount(100);
        article.setCommentCount(100);
        when(articleTagMapper.selectTagNamesByArticleId(1L)).thenReturn(List.of("Other", "Redis"));
        Map<String, Double> tagProfile = new java.util.LinkedHashMap<>();
        tagProfile.put("Redis", 10D);
        tagProfile.put("Other", 5D);
        when(profileService.profileTags(USER_ID, 40)).thenReturn(tagProfile);
        when(profileService.profileAuthors(USER_ID, 100)).thenReturn(Map.of(20L, 5D, 21L, 10D));
        when(eventMapper.selectRecentlyInteractedArticleIds(eq(USER_ID), any(LocalDateTime.class)))
                .thenReturn(List.of(1L));
        RecommendationCandidateService candidateService = new RecommendationCandidateService(
                articleMapper, articleTagMapper, tagMapper, eventMapper, profileService,
                elasticsearchOperations, rankingService, CLOCK);

        RecommendationCandidate features = candidateService.assembleFeatures(USER_ID, article);

        assertThat(features.tags()).containsExactly("Redis", "Other");
        assertThat(features.tagAffinity()).isEqualTo(1D);
        assertThat(features.authorAffinity()).isEqualTo(0.5D);
        assertThat(features.heatScore()).isCloseTo(1_300D / 2_300D, within(0.000_001));
        assertThat(features.freshnessScore()).isEqualTo(1D);
        assertThat(features.readPenalty()).isEqualTo(2D);
        assertThat(features.similarScore()).isZero();
    }

    @Test
    void elasticsearchFailureDropsOnlyTheSimilarSource() {
        ArticleMapper articleMapper = mock(ArticleMapper.class);
        ArticleTagMapper articleTagMapper = mock(ArticleTagMapper.class);
        TagMapper tagMapper = mock(TagMapper.class);
        UserArticleEventMapper eventMapper = mock(UserArticleEventMapper.class);
        RecommendationProfileService profileService = mock(RecommendationProfileService.class);
        ElasticsearchOperations elasticsearchOperations = mock(ElasticsearchOperations.class);
        when(articleMapper.selectLegacyPublishedByFollowedAuthors(eq(USER_ID), eq(USER_ID), any(), eq(20)))
                .thenReturn(List.of());
        Article explore = article(9, 90, 1, 0);
        when(articleMapper.selectLegacyPublishedHotFresh(eq(USER_ID), any(), eq(20))).thenReturn(List.of(explore));
        when(articleTagMapper.selectTagNamesByArticleId(9L)).thenReturn(List.of());
        when(profileService.profileTags(USER_ID, 40)).thenReturn(Map.of());
        when(profileService.profileAuthors(USER_ID, 100)).thenReturn(Map.of());
        when(eventMapper.selectRecentSeedArticleIds(eq(USER_ID), any(LocalDateTime.class), eq(5)))
                .thenReturn(List.of(88L));
        when(eventMapper.selectRecentlyInteractedArticleIds(eq(USER_ID), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(elasticsearchOperations.search(any(Query.class), eq(ArticleDoc.class)))
                .thenThrow(new IllegalStateException("ES unavailable"));
        RecommendationCandidateService candidateService = new RecommendationCandidateService(
                articleMapper, articleTagMapper, tagMapper, eventMapper, profileService,
                elasticsearchOperations, rankingService, CLOCK);

        List<RecommendationCandidate> result = candidateService.recallAndRank(USER_ID, Set.of(), 10);

        assertThat(result).extracting(RecommendationCandidate::articleId).containsExactly(9L);
        assertThat(result.getFirst().sources()).containsExactly(Source.EXPLORE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mysqlHydrationFailureAfterSuccessfulElasticsearchRecallIsNotSwallowed() {
        ArticleMapper articleMapper = mock(ArticleMapper.class);
        ArticleTagMapper articleTagMapper = mock(ArticleTagMapper.class);
        TagMapper tagMapper = mock(TagMapper.class);
        UserArticleEventMapper eventMapper = mock(UserArticleEventMapper.class);
        RecommendationProfileService profileService = mock(RecommendationProfileService.class);
        ElasticsearchOperations elasticsearchOperations = mock(ElasticsearchOperations.class);
        SearchHits<ArticleDoc> hits = mock(SearchHits.class);
        SearchHit<ArticleDoc> hit = mock(SearchHit.class);
        ArticleDoc document = new ArticleDoc();
        document.setId(99L);
        when(hit.getContent()).thenReturn(document);
        when(hits.stream()).thenReturn(Stream.of(hit));
        when(eventMapper.selectRecentSeedArticleIds(eq(USER_ID), any(LocalDateTime.class), eq(5)))
                .thenReturn(List.of(88L));
        when(elasticsearchOperations.search(any(Query.class), eq(ArticleDoc.class))).thenReturn(hits);
        when(articleMapper.selectLegacyPublishedByIds(List.of(99L)))
                .thenThrow(new IllegalStateException("MySQL unavailable"));
        RecommendationCandidateService candidateService = new RecommendationCandidateService(
                articleMapper, articleTagMapper, tagMapper, eventMapper, profileService,
                elasticsearchOperations, rankingService, CLOCK);

        assertThatThrownBy(() -> candidateService.recallSimilar(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MySQL unavailable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void similarOverfetchesAndBackfillsAfterFilteringSeedShownAndSelfHits() {
        ArticleMapper articleMapper = mock(ArticleMapper.class);
        ArticleTagMapper articleTagMapper = mock(ArticleTagMapper.class);
        TagMapper tagMapper = mock(TagMapper.class);
        UserArticleEventMapper eventMapper = mock(UserArticleEventMapper.class);
        RecommendationProfileService profileService = mock(RecommendationProfileService.class);
        ElasticsearchOperations elasticsearchOperations = mock(ElasticsearchOperations.class);
        SearchHits<ArticleDoc> hits = mock(SearchHits.class);
        List<SearchHit<ArticleDoc>> orderedHits = Stream.concat(
                Stream.of(hit(900L, 20L), hit(901L, 21L), hit(902L, USER_ID)),
                IntStream.rangeClosed(903, 932).mapToObj(id -> hit((long) id, 30L + id))).toList();
        when(hits.stream()).thenReturn(orderedHits.stream());
        when(eventMapper.selectRecentSeedArticleIds(eq(USER_ID), any(LocalDateTime.class), eq(5)))
                .thenReturn(List.of(900L));
        when(elasticsearchOperations.search(any(Query.class), eq(ArticleDoc.class))).thenReturn(hits);
        List<Article> hydrated = IntStream.rangeClosed(903, 932)
                .mapToObj(id -> article(id, 30L + id, 1, 0)).toList();
        when(articleMapper.selectLegacyPublishedByIds(any())).thenReturn(hydrated);
        RecommendationCandidateService candidateService = new RecommendationCandidateService(
                articleMapper, articleTagMapper, tagMapper, eventMapper, profileService,
                elasticsearchOperations, rankingService, CLOCK);

        List<Article> result = candidateService.recallSimilar(USER_ID, Set.of(901L));

        assertThat(result).extracting(Article::getId)
                .containsExactlyElementsOf(IntStream.rangeClosed(903, 932).mapToObj(Long::valueOf).toList());
        verify(articleMapper).selectLegacyPublishedByIds(
                IntStream.rangeClosed(903, 932).mapToObj(Long::valueOf).toList());
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ArticleDoc.class));
        assertThat(queryCaptor.getValue().getPageable().getPageSize()).isEqualTo(33);
        assertThat(((StringQuery) queryCaptor.getValue()).getSource())
                .contains("must_not", "authorId", "900", "901");
    }

    private static RecommendationCandidate candidate(Article article, Set<Source> sources, Set<String> tags,
                                                      double tagAffinity, double authorAffinity,
                                                      double similarScore, double heatScore,
                                                      double freshnessScore, double readPenalty) {
        return RecommendationCandidate.unranked(article, sources, tags, tagAffinity, authorAffinity,
                similarScore, heatScore, freshnessScore, readPenalty);
    }

    private static Article article(long id, long authorId, int status, int isDeleted) {
        Article article = new Article();
        article.setId(id);
        article.setAuthorId(authorId);
        article.setStatus(status);
        article.setIsDeleted(isDeleted);
        article.setCreateTime(LocalDateTime.now(CLOCK).minusDays(1));
        article.setViewCount(0);
        article.setLikeCount(0);
        article.setCollectCount(0);
        article.setCommentCount(0);
        return article;
    }

    @SuppressWarnings("unchecked")
    private static SearchHit<ArticleDoc> hit(long id, long authorId) {
        SearchHit<ArticleDoc> hit = mock(SearchHit.class);
        ArticleDoc document = new ArticleDoc();
        document.setId(id);
        document.setAuthorId(authorId);
        when(hit.getContent()).thenReturn(document);
        return hit;
    }

    private static List<Article> articles(int firstId, int count) {
        return IntStream.range(firstId, firstId + count)
                .mapToObj(id -> article(id, 1_000L + id, 1, 0))
                .toList();
    }

    private static int sourceCount(List<RecommendationCandidate> candidates, Source source) {
        return (int) candidates.stream().filter(candidate -> candidate.sources().contains(source)).count();
    }

    private static int maxConsecutiveAuthorCount(List<RecommendationCandidate> candidates) {
        int max = 0;
        int current = 0;
        Long previous = null;
        for (RecommendationCandidate candidate : candidates) {
            if (candidate.authorId().equals(previous)) {
                current++;
            } else {
                previous = candidate.authorId();
                current = 1;
            }
            max = Math.max(max, current);
        }
        return max;
    }

    private static int maxTagFrequency(List<RecommendationCandidate> candidates) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        candidates.stream().limit(10).flatMap(candidate -> candidate.tags().stream())
                .forEach(tag -> counts.merge(tag, 1, Integer::sum));
        return counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private static Set<String> linkedSet(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }
}
