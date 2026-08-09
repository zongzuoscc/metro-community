package cumt.zongzuo.community.recommendation.dto;

public record RecommendationSessionItem(
        Long articleId,
        String reason,
        String source,
        RecommendationFeatureSnapshot snapshot,
        Double baselineScore) {

    public RecommendationSessionItem(Long articleId, String reason, String source) {
        this(articleId, reason, source, null, null);
    }

    public RecommendationSessionItem(Long articleId, String reason, String source,
                                     RecommendationFeatureSnapshot snapshot) {
        this(articleId, reason, source, snapshot, null);
    }
}
