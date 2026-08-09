package cumt.zongzuo.community.recommendation.dto;

public record RecommendationExposureDraft(
        Long articleId,
        String source,
        RecommendationFeatureSnapshot snapshot,
        Double baselineScore) {

    public RecommendationExposureDraft(Long articleId, String source, RecommendationFeatureSnapshot snapshot) {
        this(articleId, source, snapshot, null);
    }
}
