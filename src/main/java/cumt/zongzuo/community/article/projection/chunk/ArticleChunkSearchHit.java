package cumt.zongzuo.community.article.projection.chunk;

public record ArticleChunkSearchHit(long chunkId, long articleId, long revisionId, float score) {
}
