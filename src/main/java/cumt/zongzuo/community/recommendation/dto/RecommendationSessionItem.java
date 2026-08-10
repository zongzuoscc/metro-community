package cumt.zongzuo.community.recommendation.dto;

public record RecommendationSessionItem(
        Long articleId,
        String reason,
        String source,
        RecommendationFeatureSnapshot snapshot,
        Double baselineScore,
        Long publishedRevisionId,
        String contentHash) {

    public RecommendationSessionItem(Long articleId, String reason, String source) {
        this(articleId, reason, source, null, null, null, null);
    }

    public RecommendationSessionItem(Long articleId, String reason, String source,
                                     RecommendationFeatureSnapshot snapshot) {
        this(articleId, reason, source, snapshot, null, null, null);
    }

    public RecommendationSessionItem(Long articleId, String reason, String source,
                                     RecommendationFeatureSnapshot snapshot,
                                     Double baselineScore) {
        this(articleId, reason, source, snapshot, baselineScore, null, null);
    }
}
