package cumt.zongzuo.community.recommendation.dto;

public record RecommendationSessionItem(
        Long articleId,
        String reason,
        String source,
        RecommendationFeatureSnapshot snapshot) {

    public RecommendationSessionItem(Long articleId, String reason, String source) {
        this(articleId, reason, source, null);
    }
}
