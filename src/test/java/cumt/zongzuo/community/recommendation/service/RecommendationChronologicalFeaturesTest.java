package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.ArticleTagMapper;
import cumt.zongzuo.community.mapper.TagMapper;
import cumt.zongzuo.community.recommendation.mapper.UserArticleEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RecommendationChronologicalFeaturesTest {

    @Test
    void chronologicalFeaturesKeepArticleSignalsWithoutReadingProfiles() {
        ArticleMapper articleMapper = mock(ArticleMapper.class);
        ArticleTagMapper articleTagMapper = mock(ArticleTagMapper.class);
        TagMapper tagMapper = mock(TagMapper.class);
        UserArticleEventMapper eventMapper = mock(UserArticleEventMapper.class);
        RecommendationProfileService profileService = mock(RecommendationProfileService.class);
        ElasticsearchOperations elasticsearch = mock(ElasticsearchOperations.class);
        RecommendationRankingService rankingService = mock(RecommendationRankingService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-09T12:00:00Z"), ZoneOffset.UTC);
        RecommendationCandidateService service = new RecommendationCandidateService(
                articleMapper, articleTagMapper, tagMapper, eventMapper, profileService,
                elasticsearch, rankingService, clock);
        Article article = new Article();
        article.setId(1L);
        article.setAuthorId(2L);
        article.setViewCount(100);
        article.setLikeCount(10);
        article.setCollectCount(3);
        article.setCommentCount(5);
        article.setCreateTime(LocalDateTime.of(2026, 8, 9, 11, 0));

        RecommendationCandidate candidate = service.assembleChronologicalFeatures(article);

        assertThat(candidate.tagAffinity()).isZero();
        assertThat(candidate.authorAffinity()).isZero();
        assertThat(candidate.similarScore()).isZero();
        assertThat(candidate.heatScore()).isPositive();
        assertThat(candidate.freshnessScore()).isBetween(0D, 1D);
        verifyNoInteractions(profileService, eventMapper, articleTagMapper, elasticsearch, rankingService);
    }
}
