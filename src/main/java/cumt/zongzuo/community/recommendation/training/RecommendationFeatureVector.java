package cumt.zongzuo.community.recommendation.training;

import java.util.List;

public record RecommendationFeatureVector(
        double tagAffinity,
        double authorAffinity,
        double similarScore,
        double heatScore,
        double freshnessScore,
        double sourceFollow,
        double sourceTag,
        double sourceSimilar,
        double sourceExplore) {

    public static final List<String> FEATURE_NAMES = List.of(
            "tagAffinity", "authorAffinity", "similarScore", "heatScore", "freshnessScore",
            "sourceFollow", "sourceTag", "sourceSimilar", "sourceExplore");

    public RecommendationFeatureVector {
        for (double value : new double[]{tagAffinity, authorAffinity, similarScore, heatScore, freshnessScore,
                sourceFollow, sourceTag, sourceSimilar, sourceExplore}) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Feature values must be finite");
            }
        }
    }

    public double[] values() {
        return new double[]{tagAffinity, authorAffinity, similarScore, heatScore, freshnessScore,
                sourceFollow, sourceTag, sourceSimilar, sourceExplore};
    }

    public static RecommendationFeatureVector from(double[] values) {
        if (values == null || values.length != FEATURE_NAMES.size()) {
            throw new IllegalArgumentException("Expected exactly nine features");
        }
        return new RecommendationFeatureVector(values[0], values[1], values[2], values[3], values[4],
                values[5], values[6], values[7], values[8]);
    }
}
