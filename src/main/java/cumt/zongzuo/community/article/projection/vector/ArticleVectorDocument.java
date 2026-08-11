package cumt.zongzuo.community.article.projection.vector;

import java.util.Objects;

public record ArticleVectorDocument(long chunkId,
                                    float[] embedding,
                                    long articleId,
                                    long revisionId,
                                    long authorId,
                                    int chunkNo,
                                    String contentHash,
                                    boolean active,
                                    long publishedAtEpoch,
                                    String language,
                                    String embeddingModel,
                                    String parserVersion,
                                    long parserGeneration,
                                    long lifecycleEpoch,
                                    long aggregateVersion,
                                    long sourceAggregateVersion) {

    public ArticleVectorDocument {
        Objects.requireNonNull(embedding, "embedding");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(embeddingModel, "embeddingModel");
        Objects.requireNonNull(parserVersion, "parserVersion");
        embedding = embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}
