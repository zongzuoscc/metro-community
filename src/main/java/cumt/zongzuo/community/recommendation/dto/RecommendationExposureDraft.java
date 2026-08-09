package cumt.zongzuo.community.recommendation.dto;

public record RecommendationExposureDraft(
        Long articleId,
        Long articleAuthorId,
        String source,
        RecommendationFeatureSnapshot snapshot,
        Double baselineScore) {

    public RecommendationExposureDraft(Long articleId, Long articleAuthorId, String source,
                                       RecommendationFeatureSnapshot snapshot) {
        this(articleId, articleAuthorId, source, snapshot, null);
    }
}
