package cumt.zongzuo.community.recommendation.dto;

public record RecommendationSessionItem(
        Long articleId,
        String reason,
        String source) {
}
