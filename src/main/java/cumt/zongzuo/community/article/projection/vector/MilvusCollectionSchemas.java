package cumt.zongzuo.community.article.projection.vector;

import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class MilvusCollectionSchemas {

    public static final String ARTICLE_COLLECTION = "metro_article_chunks_bgem3_v1";
    public static final String ARTICLE_ALIAS = "metro_article_chunks_read";
    public static final String MEMORY_COLLECTION = "metro_user_memories_bgem3_v1";
    public static final String MEMORY_ALIAS = "metro_user_memories_read";
    public static final int EMBEDDING_DIMENSION = 1024;

    private MilvusCollectionSchemas() {
    }

    public static CreateCollectionReq article() {
        List<CreateCollectionReq.FieldSchema> fields = List.of(
                primary("chunk_id"),
                vector(),
                scalar("article_id", DataType.Int64),
                scalar("revision_id", DataType.Int64),
                scalar("author_id", DataType.Int64),
                scalar("chunk_no", DataType.Int32),
                varchar("content_hash", 64),
                scalar("is_active", DataType.Bool),
                scalar("published_at_epoch", DataType.Int64),
                varchar("language", 16),
                varchar("embedding_model", 64),
                varchar("parser_version", 32),
                scalar("parser_generation", DataType.Int64),
                scalar("lifecycle_epoch", DataType.Int64),
                scalar("aggregate_version", DataType.Int64),
                scalar("source_aggregate_version", DataType.Int64));
        return request(ARTICLE_COLLECTION, fields, articleIndexes(), null);
    }

    public static CreateCollectionReq memory() {
        List<CreateCollectionReq.FieldSchema> fields = List.of(
                primary("memory_version_id"),
                vector(),
                scalar("memory_id", DataType.Int64),
                partitionKey("user_id"),
                varchar("category", 24),
                varchar("sensitivity", 16),
                scalar("is_active", DataType.Bool),
                scalar("expires_at_epoch", DataType.Int64),
                varchar("content_hash", 64),
                varchar("embedding_model", 64),
                scalar("lifecycle_epoch", DataType.Int64),
                scalar("aggregate_version", DataType.Int64));
        return request(MEMORY_COLLECTION, fields, memoryIndexes(), 64);
    }

    public static String fingerprint(CreateCollectionReq request) {
        StringBuilder contract = new StringBuilder()
                .append(request.getCollectionName()).append('|')
                .append(request.getEnableDynamicField()).append('|')
                .append(request.getAutoID()).append('|')
                .append(request.getNumPartitions()).append('\n');
        for (CreateCollectionReq.FieldSchema field : request.getCollectionSchema().getFieldSchemaList()) {
            contract.append(field.getName()).append(':').append(field.getDataType()).append(':')
                    .append(field.getDataType() == DataType.VarChar ? field.getMaxLength() : "-").append(':')
                    .append(field.getDimension()).append(':').append(field.getIsPrimaryKey()).append(':')
                    .append(field.getIsPartitionKey()).append(':').append(field.getIsNullable()).append('\n');
        }
        for (IndexParam index : request.getIndexParams()) {
            contract.append(index.getIndexName()).append(':').append(index.getFieldName()).append(':')
                    .append(index.getIndexType()).append(':').append(index.getMetricType()).append(':')
                    .append(index.getExtraParams()).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(contract.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static CreateCollectionReq request(String name,
                                               List<CreateCollectionReq.FieldSchema> fields,
                                               List<IndexParam> indexes,
                                               Integer partitions) {
        CreateCollectionReq.CreateCollectionReqBuilder builder = CreateCollectionReq.builder()
                .collectionName(name)
                .collectionSchema(CreateCollectionReq.CollectionSchema.builder()
                        .enableDynamicField(false)
                        .fieldSchemaList(fields)
                        .build())
                .enableDynamicField(false)
                .autoID(false)
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .indexParams(indexes);
        if (partitions != null) {
            builder.numPartitions(partitions);
        }
        return builder.build();
    }

    private static CreateCollectionReq.FieldSchema primary(String name) {
        return base(name, DataType.Int64)
                .isPrimaryKey(true)
                .autoID(false)
                .build();
    }

    private static CreateCollectionReq.FieldSchema vector() {
        return base("embedding", DataType.FloatVector)
                .dimension(EMBEDDING_DIMENSION)
                .build();
    }

    private static CreateCollectionReq.FieldSchema partitionKey(String name) {
        return base(name, DataType.Int64)
                .isPartitionKey(true)
                .build();
    }

    private static CreateCollectionReq.FieldSchema scalar(String name, DataType type) {
        return base(name, type).build();
    }

    private static CreateCollectionReq.FieldSchema varchar(String name, int maxLength) {
        return base(name, DataType.VarChar).maxLength(maxLength).build();
    }

    private static CreateCollectionReq.FieldSchema.FieldSchemaBuilder base(String name, DataType type) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name)
                .dataType(type)
                .isPrimaryKey(false)
                .isPartitionKey(false)
                .isClusteringKey(false)
                .autoID(false)
                .isNullable(false);
    }

    private static List<IndexParam> articleIndexes() {
        return List.of(
                vectorIndex(),
                scalarIndex("is_active", "idx_article_active_bitmap", IndexParam.IndexType.BITMAP),
                scalarIndex("article_id", "idx_article_article_id_inverted", IndexParam.IndexType.INVERTED),
                scalarIndex("revision_id", "idx_article_revision_id_inverted", IndexParam.IndexType.INVERTED),
                scalarIndex("author_id", "idx_article_author_id_inverted", IndexParam.IndexType.INVERTED),
                scalarIndex("language", "idx_article_language_inverted", IndexParam.IndexType.INVERTED),
                scalarIndex("published_at_epoch", "idx_article_published_at_epoch_inverted",
                        IndexParam.IndexType.INVERTED),
                scalarIndex("embedding_model", "idx_article_embedding_model_inverted",
                        IndexParam.IndexType.INVERTED));
    }

    private static List<IndexParam> memoryIndexes() {
        return List.of(
                vectorIndex(),
                scalarIndex("is_active", "idx_memory_active_bitmap", IndexParam.IndexType.BITMAP),
                scalarIndex("sensitivity", "idx_memory_sensitivity_bitmap", IndexParam.IndexType.BITMAP),
                scalarIndex("memory_id", "idx_memory_id_inverted", IndexParam.IndexType.INVERTED),
                scalarIndex("category", "idx_memory_category_inverted", IndexParam.IndexType.INVERTED),
                scalarIndex("expires_at_epoch", "idx_memory_expires_at_epoch_inverted",
                        IndexParam.IndexType.INVERTED),
                scalarIndex("content_hash", "idx_memory_content_hash_inverted", IndexParam.IndexType.INVERTED),
                scalarIndex("embedding_model", "idx_memory_embedding_model_inverted",
                        IndexParam.IndexType.INVERTED));
    }

    private static IndexParam vectorIndex() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("M", 16);
        parameters.put("efConstruction", 256);
        return IndexParam.builder()
                .fieldName("embedding")
                .indexName("idx_embedding_hnsw")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(parameters)
                .build();
    }

    private static IndexParam scalarIndex(String field, String name, IndexParam.IndexType type) {
        return IndexParam.builder()
                .fieldName(field)
                .indexName(name)
                .indexType(type)
                .metricType(IndexParam.MetricType.INVALID)
                .extraParams(Map.of())
                .build();
    }
}
