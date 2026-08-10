package cumt.zongzuo.community.article.model;

import java.util.List;

public record ArticleContentSnapshot(
        String title,
        String summary,
        String bodyMarkdown,
        String bodyPlain,
        String cover,
        List<String> tags,
        String tagsJson,
        String contentHash
) {
    public ArticleContentSnapshot {
        tags = List.copyOf(tags);
    }
}
