package cumt.zongzuo.community.article.projection.vector;

public record ArticleVectorHit(long chunkId, long articleId, long revisionId, float score) {
}
