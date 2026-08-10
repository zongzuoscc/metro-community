package cumt.zongzuo.community.article.web;

public record SubmissionResult(
        long articleId,
        long revisionId,
        long revisionNo,
        long moderationJobId,
        String contentHash
) {
}
