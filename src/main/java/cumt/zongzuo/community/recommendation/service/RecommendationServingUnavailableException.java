package cumt.zongzuo.community.recommendation.service;

public class RecommendationServingUnavailableException extends RuntimeException {
    public RecommendationServingUnavailableException(String message) {
        super(message);
    }

    public RecommendationServingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
