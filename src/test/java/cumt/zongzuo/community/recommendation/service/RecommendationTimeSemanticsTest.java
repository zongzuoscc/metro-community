package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.ArticleTagMapper;
import cumt.zongzuo.community.mapper.TagMapper;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import cumt.zongzuo.community.recommendation.mapper.UserArticleEventMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationTimeSemanticsTest {
    private static final Clock FRACTIONAL_CLOCK = Clock.fixed(
            Instant.parse("2026-08-09T12:00:00.987654321Z"), ZoneOffset.UTC);

    @Test
    void eligibilityTruncatesBothInclusiveDatetimeCutoffsToSeconds() {
        UserArticleEventMapper events = mock(UserArticleEventMapper.class);
        RecommendationProperties properties = new RecommendationProperties();
        when(events.countUserFactsSince(eq(7L), any(LocalDateTime.class))).thenReturn(20L);
        when(events.countGlobalFactsSince(any(LocalDateTime.class))).thenReturn(500L);
        RecommendationEligibilityService service = new RecommendationEligibilityService(
                events, properties, FRACTIONAL_CLOCK);

        assertThat(service.isEligible(7L)).isTrue();

        ArgumentCaptor<LocalDateTime> userCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> globalCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(events).countUserFactsSince(eq(7L), userCutoff.capture());
        verify(events).countGlobalFactsSince(globalCutoff.capture());
        assertThat(userCutoff.getValue()).isEqualTo(LocalDateTime.of(2026, 7, 10, 12, 0));
        assertThat(globalCutoff.getValue()).isEqualTo(LocalDateTime.of(2026, 5, 11, 12, 0));
    }

    @Test
    void candidateQueriesUseSecondPrecisionDatetimeCutoffsOnEveryPath() {
        ArticleMapper articles = mock(ArticleMapper.class);
        ArticleTagMapper articleTags = mock(ArticleTagMapper.class);
        TagMapper tags = mock(TagMapper.class);
        UserArticleEventMapper events = mock(UserArticleEventMapper.class);
        RecommendationProfileService profiles = mock(RecommendationProfileService.class);
        when(profiles.profileTags(7L, 40)).thenReturn(Map.of());
        when(profiles.profileAuthors(7L, 100)).thenReturn(Map.of());
        when(events.selectRecentlyInteractedArticleIds(eq(7L), any(LocalDateTime.class))).thenReturn(List.of());
        when(events.selectRecentSeedArticleIds(eq(7L), any(LocalDateTime.class), eq(5))).thenReturn(List.of());
        RecommendationCandidateService service = new RecommendationCandidateService(articles, articleTags, tags,
                events, profiles, mock(ElasticsearchOperations.class), new RecommendationRankingService(), FRACTIONAL_CLOCK);
        Article article = new Article();
        article.setId(1L);
        article.setAuthorId(2L);

        service.recallAndAssemble(7L, Set.of());
        service.assembleFeatures(7L, article);
        service.recallSimilar(7L);

        LocalDateTime expected = LocalDateTime.of(2026, 7, 10, 12, 0);
        ArgumentCaptor<LocalDateTime> interactedCutoffs = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> seedCutoffs = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(events, times(2)).selectRecentlyInteractedArticleIds(eq(7L), interactedCutoffs.capture());
        verify(events, times(2)).selectRecentSeedArticleIds(eq(7L), seedCutoffs.capture(), eq(5));
        assertThat(interactedCutoffs.getAllValues()).containsOnly(expected);
        assertThat(seedCutoffs.getAllValues()).containsOnly(expected);
    }
}
