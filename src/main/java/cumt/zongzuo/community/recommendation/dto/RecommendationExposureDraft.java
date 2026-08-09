package cumt.zongzuo.community.recommendation.dto;

public record RecommendationExposureDraft(
        Long articleId,
        String source,
        RecommendationFeatureSnapshot snapshot) {
}
