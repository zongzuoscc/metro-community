package cumt.zongzuo.community.ai.moderation.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

public record ModerationJobResponse(
        long id,
        long articleId,
        long revisionId,
        String contentHash,
        String state,
        String modelDecision,
        BigDecimal riskScore,
        JsonNode policyHits,
        int attemptCount,
        String lastError,
        long jobVersion,
        long articleVersion,
        long lifecycleEpoch,
        Long currentPublishedRevisionId,
        ModerationRevisionResponse revision) {
}
