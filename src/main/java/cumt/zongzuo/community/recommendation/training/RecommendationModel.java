package cumt.zongzuo.community.recommendation.training;

import java.time.Instant;
import java.util.List;

public record RecommendationModel(
        String version,
        Instant trainedAt,
        List<String> featureNames,
        List<Double> means,
        List<Double> standardDeviations,
        List<Double> weights,
        double bias,
        double validationAuc,
        double baselineAuc) {

    public RecommendationModel {
        if (version == null || version.isBlank() || trainedAt == null
                || !RecommendationFeatureVector.FEATURE_NAMES.equals(featureNames)
                || !validVector(means, false) || !validVector(standardDeviations, true)
                || !validVector(weights, false) || !Double.isFinite(bias)
                || !validAuc(validationAuc) || !validAuc(baselineAuc)) {
            throw new IllegalArgumentException("Invalid recommendation model");
        }
        featureNames = List.copyOf(featureNames);
        means = List.copyOf(means);
        standardDeviations = List.copyOf(standardDeviations);
        weights = List.copyOf(weights);
    }

    public double score(RecommendationFeatureVector vector) {
        double[] values = vector.values();
        double z = bias;
        for (int i = 0; i < values.length; i++) {
            z += ((values[i] - means.get(i)) / standardDeviations.get(i)) * weights.get(i);
        }
        if (!Double.isFinite(z)) {
            throw new IllegalStateException("Non-finite model score");
        }
        double clipped = Math.max(-35D, Math.min(35D, z));
        return 1D / (1D + Math.exp(-clipped));
    }

    private static boolean validVector(List<Double> values, boolean strictlyPositive) {
        return values != null && values.size() == RecommendationFeatureVector.FEATURE_NAMES.size()
                && values.stream().allMatch(value -> value != null && Double.isFinite(value)
                && (!strictlyPositive || value > 0D));
    }

    private static boolean validAuc(double value) {
        return Double.isFinite(value) && value >= 0D && value <= 1D;
    }
}
