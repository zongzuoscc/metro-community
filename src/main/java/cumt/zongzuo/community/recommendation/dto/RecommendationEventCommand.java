package cumt.zongzuo.community.recommendation.dto;

import cumt.zongzuo.community.recommendation.entity.RecommendationEventType;

import java.time.LocalDateTime;

public record RecommendationEventCommand(
        Long userId,
        Long articleId,
        Long targetAuthorId,
        RecommendationEventType eventType,
        LocalDateTime occurredAt,
        String dedupeKey,
        String source) {
}
