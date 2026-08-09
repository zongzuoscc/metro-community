package cumt.zongzuo.community.recommendation;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.recommendation.service.RecommendationCandidate;
import cumt.zongzuo.community.recommendation.service.RecommendationCandidate.Source;
import cumt.zongzuo.community.recommendation.service.RecommendationCandidateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationRecallIntegrationTest extends IntegrationTestSupport {

    private static final long USER_ID = 7_001L;
    private static final long OTHER_AUTHOR_ID = 9_001L;
    private static final long TAG_ID = 8_001L;
    private static final String TAG_NAME = "backfill";

    @Autowired
    private RecommendationCandidateService candidateService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM recommendation_exposure");
        jdbcTemplate.update("DELETE FROM user_article_event");
        jdbcTemplate.update("DELETE FROM follow");
        jdbcTemplate.update("DELETE FROM article_tag");
        jdbcTemplate.update("DELETE FROM tag");
        jdbcTemplate.update("DELETE FROM article");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void exploreFiltersShownAndSelfBeforeApplyingItsLimit() {
        Set<Long> shownArticleIds = insertExcludedThenOneEligible(20);

        List<RecommendationCandidate> recommendations = candidateService.recallAndRank(USER_ID, shownArticleIds, 20);

        assertThat(recommendations).extracting(RecommendationCandidate::articleId).containsExactly(21L);
        assertThat(recommendations).extracting(RecommendationCandidate::articleId)
                .doesNotContainAnyElementsOf(shownArticleIds);
        assertThat(recommendations).extracting(RecommendationCandidate::authorId).doesNotContain(USER_ID);
    }

    @Test
    void tagFiltersShownAndSelfBeforeApplyingItsLimit() {
        Set<Long> shownArticleIds = insertExcludedThenOneEligible(40);
        jdbcTemplate.update("INSERT INTO tag (id, name, article_count, create_time) VALUES (?, ?, 41, NOW())",
                TAG_ID, TAG_NAME);
        for (long articleId = 1; articleId <= 41; articleId++) {
            jdbcTemplate.update("INSERT INTO article_tag (article_id, tag_id) VALUES (?, ?)", articleId, TAG_ID);
        }
        redisTemplate.opsForZSet().add("recommendation:tag:" + USER_ID, TAG_NAME, 1D);

        List<RecommendationCandidate> recommendations = candidateService.recallAndRank(USER_ID, shownArticleIds, 20);

        assertThat(recommendations).extracting(RecommendationCandidate::articleId).containsExactly(41L);
        assertThat(recommendations.getFirst().sources()).contains(Source.TAG);
        assertThat(recommendations).extracting(RecommendationCandidate::articleId)
                .doesNotContainAnyElementsOf(shownArticleIds);
        assertThat(recommendations).extracting(RecommendationCandidate::authorId).doesNotContain(USER_ID);
    }

    private Set<Long> insertExcludedThenOneEligible(int excludedCount) {
        Set<Long> shownArticleIds = new LinkedHashSet<>();
        long lastArticleId = excludedCount + 1L;
        long shownCount = excludedCount / 2L;
        for (long articleId = 1; articleId <= lastArticleId; articleId++) {
            long authorId = articleId <= shownCount ? 10_000L + articleId
                    : articleId <= excludedCount ? USER_ID : OTHER_AUTHOR_ID;
            int creationOffsetSeconds = articleId <= excludedCount ? (int) (100 - articleId) : 0;
            if (articleId <= shownCount) {
                shownArticleIds.add(articleId);
            }
            jdbcTemplate.update("""
                    INSERT INTO article
                        (id, title, summary, content, author_id, view_count, like_count, collect_count,
                         comment_count, status, is_deleted, create_time, update_time)
                    VALUES (?, ?, 'summary', 'content', ?, ?, 0, 0, 0, 1, 0,
                            DATE_ADD(NOW(), INTERVAL ? SECOND), DATE_ADD(NOW(), INTERVAL ? SECOND))
                    """, articleId, "candidate-" + articleId, authorId, 200 - articleId,
                    creationOffsetSeconds, creationOffsetSeconds);
        }
        return shownArticleIds;
    }
}
