package cumt.zongzuo.community.article.projection.vector;

import java.util.List;

public interface ArticleVectorRepository {

    long upsert(String physicalCollection, List<ArticleVectorDocument> documents);

    List<ArticleVectorHit> searchActive(String readAlias,
                                        float[] embedding,
                                        int topK,
                                        String embeddingModel,
                                        long parserGeneration);

    long deleteByChunkIds(String physicalCollection, List<Long> chunkIds);

    void assertDeletedStrong(String physicalCollection, List<Long> chunkIds);
}
