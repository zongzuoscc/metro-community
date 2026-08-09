package cumt.zongzuo.community.recommendation.dto;

import cumt.zongzuo.community.entity.Article;

public record RecommendationItem(
        Article article,
        String reason,
        String source,
        Long exposureId) {

    public RecommendationItem withExposureId(Long id) {
        return new RecommendationItem(article, reason, source, id);
    }
}
