package cumt.zongzuo.community.article.web;

public record SubmitArticleRevisionCommand(
        long articleId,
        long userId,
        long expectedDraftVersion
) {
}
