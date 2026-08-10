package cumt.zongzuo.community.article.web;

import java.util.List;

public record SaveArticleDraftCommand(
        Long articleId,
        long expectedDraftVersion,
        String title,
        String summary,
        String bodyMarkdown,
        String cover,
        List<String> tags
) {
    public SaveArticleDraftCommand {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
