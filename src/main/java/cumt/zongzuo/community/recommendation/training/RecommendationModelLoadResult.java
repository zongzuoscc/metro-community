package cumt.zongzuo.community.recommendation.training;

import java.util.Optional;

public record RecommendationModelLoadResult(Status status, Optional<RecommendationModel> model) {
    public enum Status { AVAILABLE, ABSENT, INVALID, EXPIRED, IO_FAILURE }

    public RecommendationModelLoadResult {
        model = model == null ? Optional.empty() : model;
    }

    public static RecommendationModelLoadResult available(RecommendationModel model) {
        return new RecommendationModelLoadResult(Status.AVAILABLE, Optional.of(model));
    }

    public static RecommendationModelLoadResult unavailable(Status status) {
        return new RecommendationModelLoadResult(status, Optional.empty());
    }
}
