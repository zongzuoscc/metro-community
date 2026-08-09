package cumt.zongzuo.community.recommendation.dto;

import java.util.List;

public record RecommendationSession(
        Long userId,
        List<RecommendationSessionItem> items,
        RecommendationMode mode) {
}
