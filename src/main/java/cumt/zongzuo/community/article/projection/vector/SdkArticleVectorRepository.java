package cumt.zongzuo.community.article.projection.vector;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SdkArticleVectorRepository implements ArticleVectorRepository {

    private static final List<String> SEARCH_FIELDS = List.of("article_id", "revision_id");

    private final MilvusClientV2 client;

    public SdkArticleVectorRepository(MilvusClientV2 client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public List<Long> listChunkIdsByArticle(String physicalCollection, long articleId) {
        requireCollection(physicalCollection);
        if (articleId <= 0) {
            throw new IllegalArgumentException("articleId must be positive");
        }
        client.loadCollection(LoadCollectionReq.builder()
                .collectionName(physicalCollection)
                .sync(true)
                .timeout(60_000L)
                .build());
        QueryResp response = client.query(QueryReq.builder()
                .collectionName(physicalCollection)
                .filter("article_id == " + articleId)
                .outputFields(List.of("chunk_id"))
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build());
        if (response.getQueryResults() == null) {
            return List.of();
        }
        return response.getQueryResults().stream()
                .map(row -> number(row.getEntity().get("chunk_id"), "chunk_id").longValue())
                .sorted()
                .toList();
    }

    @Override
    public long upsert(String physicalCollection, List<ArticleVectorDocument> documents) {
        requireCollection(physicalCollection);
        Objects.requireNonNull(documents, "documents");
        if (documents.isEmpty()) {
            return 0L;
        }
        List<JsonObject> rows = documents.stream().map(SdkArticleVectorRepository::toRow).toList();
        return client.upsert(UpsertReq.builder()
                .collectionName(physicalCollection)
                .data(rows)
                .build()).getUpsertCnt();
    }

    @Override
    public List<ArticleVectorHit> searchActive(String readAlias,
                                               float[] embedding,
                                               int topK,
                                               String embeddingModel,
                                               long parserGeneration) {
        requireCollection(readAlias);
        requireVector(embedding);
        if (topK < 1 || topK > 1_024) {
            throw new IllegalArgumentException("topK must be between 1 and 1024");
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("embeddingModel must not be blank");
        }
        client.loadCollection(LoadCollectionReq.builder()
                .collectionName(readAlias)
                .sync(true)
                .timeout(60_000L)
                .build());
        Map<String, Object> filterValues = new LinkedHashMap<>();
        filterValues.put("model", embeddingModel);
        filterValues.put("generation", parserGeneration);
        SearchResp response = client.search(SearchReq.builder()
                .collectionName(readAlias)
                .annsField("embedding")
                .metricType(IndexParam.MetricType.COSINE)
                .topK(topK)
                .data(List.of(new FloatVec(embedding.clone())))
                .filter("is_active == true && embedding_model == {model} && parser_generation == {generation}")
                .filterTemplateValues(filterValues)
                .outputFields(SEARCH_FIELDS)
                .searchParams(Map.of("ef", Math.max(64, Math.multiplyExact(topK, 4))))
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .build());
        if (response.getSearchResults() == null || response.getSearchResults().isEmpty()) {
            return List.of();
        }
        List<ArticleVectorHit> hits = new ArrayList<>();
        for (SearchResp.SearchResult result : response.getSearchResults().getFirst()) {
            hits.add(new ArticleVectorHit(number(result.getId(), "chunk_id").longValue(),
                    number(result.getEntity().get("article_id"), "article_id").longValue(),
                    number(result.getEntity().get("revision_id"), "revision_id").longValue(),
                    result.getScore()));
        }
        return List.copyOf(hits);
    }

    @Override
    public long deleteByChunkIds(String physicalCollection, List<Long> chunkIds) {
        requireCollection(physicalCollection);
        Objects.requireNonNull(chunkIds, "chunkIds");
        if (chunkIds.isEmpty()) {
            return 0L;
        }
        return client.delete(DeleteReq.builder()
                .collectionName(physicalCollection)
                .ids(chunkIds.stream().map(id -> (Object) id).toList())
                .build()).getDeleteCnt();
    }

    @Override
    public void assertDeletedStrong(String physicalCollection, List<Long> chunkIds) {
        requireCollection(physicalCollection);
        Objects.requireNonNull(chunkIds, "chunkIds");
        if (chunkIds.isEmpty()) {
            return;
        }
        QueryResp response = client.query(QueryReq.builder()
                .collectionName(physicalCollection)
                .ids(chunkIds.stream().map(id -> (Object) id).toList())
                .outputFields(List.of("chunk_id"))
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build());
        if (response.getQueryResults() != null && !response.getQueryResults().isEmpty()) {
            throw new IllegalStateException("Milvus delete did not converge for chunk IDs " + chunkIds);
        }
    }

    private static JsonObject toRow(ArticleVectorDocument document) {
        requireVector(document.embedding());
        JsonObject row = new JsonObject();
        row.addProperty("chunk_id", document.chunkId());
        JsonArray embedding = new JsonArray(MilvusCollectionSchemas.EMBEDDING_DIMENSION);
        for (float value : document.embedding()) {
            embedding.add(value);
        }
        row.add("embedding", embedding);
        row.addProperty("article_id", document.articleId());
        row.addProperty("revision_id", document.revisionId());
        row.addProperty("author_id", document.authorId());
        row.addProperty("chunk_no", document.chunkNo());
        row.addProperty("content_hash", document.contentHash());
        row.addProperty("is_active", document.active());
        row.addProperty("published_at_epoch", document.publishedAtEpoch());
        row.addProperty("language", document.language());
        row.addProperty("embedding_model", document.embeddingModel());
        row.addProperty("parser_version", document.parserVersion());
        row.addProperty("parser_generation", document.parserGeneration());
        row.addProperty("lifecycle_epoch", document.lifecycleEpoch());
        row.addProperty("aggregate_version", document.aggregateVersion());
        row.addProperty("source_aggregate_version", document.sourceAggregateVersion());
        return row;
    }

    private static void requireVector(float[] vector) {
        Objects.requireNonNull(vector, "embedding");
        if (vector.length != MilvusCollectionSchemas.EMBEDDING_DIMENSION) {
            throw new IllegalArgumentException("embedding must contain exactly 1024 dimensions");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("embedding must contain only finite values");
            }
        }
    }

    private static Number number(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Milvus result field is not numeric: " + field);
        }
        return number;
    }

    private static void requireCollection(String collection) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("collection must not be blank");
        }
    }
}
