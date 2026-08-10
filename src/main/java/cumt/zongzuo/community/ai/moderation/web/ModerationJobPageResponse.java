package cumt.zongzuo.community.ai.moderation.web;

import java.util.List;

public record ModerationJobPageResponse(
        List<ModerationJobResponse> items,
        Long nextBefore,
        boolean hasMore) {
}
