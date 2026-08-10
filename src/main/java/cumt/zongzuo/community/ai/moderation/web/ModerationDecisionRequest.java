package cumt.zongzuo.community.ai.moderation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ModerationDecisionRequest(
        @Positive long revisionId,
        @PositiveOrZero long expectedJobVersion,
        @PositiveOrZero long expectedArticleVersion,
        @NotBlank @Size(max = 500) String reason) {
}
