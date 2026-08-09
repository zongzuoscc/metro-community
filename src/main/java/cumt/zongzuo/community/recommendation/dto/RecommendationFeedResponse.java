package cumt.zongzuo.community.recommendation.dto;

import java.util.List;

public record RecommendationFeedResponse(
        List<RecommendationItem> items,
        String nextCursor,
        RecommendationMode mode) {
}
