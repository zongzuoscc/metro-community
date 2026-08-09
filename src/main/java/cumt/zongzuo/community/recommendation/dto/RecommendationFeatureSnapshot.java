package cumt.zongzuo.community.recommendation.dto;

public record RecommendationFeatureSnapshot(
        double tagAffinity,
        double authorAffinity,
        double similarScore,
        double heatScore,
        double freshnessScore,
        double sourceFollow,
        double sourceTag,
        double sourceSimilar,
        double sourceExplore) {
}
