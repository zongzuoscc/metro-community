package cumt.zongzuo.community.recommendation.training;

public record TrainingExample(RecommendationFeatureVector features, int label, double baselineScore) {
    public TrainingExample {
        if (features == null || (label != 0 && label != 1) || !Double.isFinite(baselineScore)) {
            throw new IllegalArgumentException("Invalid training example");
        }
    }
}
