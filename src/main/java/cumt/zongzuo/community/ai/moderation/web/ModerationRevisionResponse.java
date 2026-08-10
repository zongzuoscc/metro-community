package cumt.zongzuo.community.ai.moderation.web;

import java.time.LocalDateTime;
import java.util.List;

public record ModerationRevisionResponse(
        long id,
        long revisionNo,
        String title,
        String summary,
        String bodyMarkdown,
        String bodyPlain,
        String cover,
        List<String> tags,
        String contentHash,
        long sourceDraftVersion,
        long createdBy,
        LocalDateTime createdAt) {
}
