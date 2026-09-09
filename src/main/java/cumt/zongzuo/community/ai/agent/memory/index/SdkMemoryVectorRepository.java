package cumt.zongzuo.community.ai.agent.memory.index;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cumt.zongzuo.community.article.projection.vector.MilvusCollectionSchemas;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.client.RetryConfig;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 独立记忆 Collection；所有读取明确指定 STRONG，不依赖 Collection 的默认一致性。
 * SDK withTimeout 会修改客户端配置，因此客户端必须专用，并在可超时锁内逐 RPC 设置剩余预算。
 */
public final class SdkMemoryVectorRepository implements MemoryVectorRepository {
    private static final List<String> VISIBILITY_FIELDS = List.of("memory_version_id", "user_id", "embedding_model", "content_hash", "is_active");
    private final MilvusClientV2 client;
    private final String collection;
    private final boolean initializeSchema;
    private final Clock clock;
    private final double minimumScore;
    private final ReentrantLock rpcLock = new ReentrantLock();
    private boolean ready;

    public SdkMemoryVectorRepository(MilvusClientV2 client, String collection, boolean initializeSchema, Clock clock) {
        this(client, collection, initializeSchema, clock, 0.35D);
    }

    public SdkMemoryVectorRepository(MilvusClientV2 client, String collection, boolean initializeSchema,
                                     Clock clock, double minimumScore) {
        this.client = Objects.requireNonNull(client);
        if (collection == null || !collection.matches("[A-Za-z_][A-Za-z0-9_]{0,254}"))
            throw new IllegalArgumentException("Invalid memory collection name");
        this.collection = collection;
        this.initializeSchema = initializeSchema;
        this.clock = Objects.requireNonNull(clock);
        if (!Double.isFinite(minimumScore) || minimumScore < -1 || minimumScore > 1)
            throw new IllegalArgumentException("Memory minimum cosine score must be -1..1");
        this.minimumScore = minimumScore;
    }

    @Override
    public void upsertAndVerify(List<MemoryVectorDocument> documents, Instant deadline) {
        Objects.requireNonNull(documents);
        if (documents.isEmpty()) return;
        if (documents.size() > 128) throw new IllegalArgumentException("Memory upsert batch exceeds 128");
        List<JsonObject> rows = documents.stream().map(this::toRow).toList();
        locked(deadline, () -> {
            ensureReady(deadline);
            var result = timed(deadline).upsert(UpsertReq.builder().collectionName(collection).data(rows).build());
            if (result == null || result.getUpsertCnt() != documents.size())
                throw new IllegalStateException("Memory vector upsert was incomplete");
            QueryResp response = timed(deadline).query(QueryReq.builder().collectionName(collection)
                    .ids(documents.stream().map(doc -> (Object) doc.source().memoryVersionId()).toList())
                    .outputFields(VISIBILITY_FIELDS).consistencyLevel(ConsistencyLevel.STRONG).build());
            Map<Long, Map<String, Object>> visible = new HashMap<>();
            for (QueryResp.QueryResult row : requireQueryRows(response)) {
                visible.put(number(row.getEntity().get("memory_version_id")), row.getEntity());
            }
            for (MemoryVectorDocument document : documents) {
                var source = document.source();
                Map<String, Object> actual = visible.get(source.memoryVersionId());
                if (actual == null || number(actual.get("user_id")) != source.userId()
                        || !document.embeddingModel().equals(actual.get("embedding_model"))
                        || !source.contentHash().equals(actual.get("content_hash"))
                        || !Boolean.TRUE.equals(actual.get("is_active")))
                    throw new IllegalStateException("Memory vector is not strongly visible with expected version and model");
            }
            return null;
        });
    }

    @Override
    public void deleteAndVerify(long userId, List<Long> versionIds, Instant deadline) {
        requireUser(userId);
        Objects.requireNonNull(versionIds);
        if (versionIds.isEmpty()) return;
        if (versionIds.size() > 128 || versionIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("Invalid memory deletion batch");
        // 此表达式仅由经校验的 long 构造，不能接收原始用户文本。
        String filter = "user_id == " + userId + " && memory_version_id in " + versionIds;
        locked(deadline, () -> {
            ensureReady(deadline);
            timed(deadline).delete(DeleteReq.builder().collectionName(collection).filter(filter).build());
            QueryResp response = timed(deadline).query(QueryReq.builder().collectionName(collection)
                    .filter(filter).outputFields(List.of("memory_version_id"))
                    .consistencyLevel(ConsistencyLevel.STRONG).build());
            if (!requireQueryRows(response).isEmpty())
                throw new IllegalStateException("Memory vector deletion is not strongly visible");
            return null;
        });
    }

    @Override
    public List<Long> search(long userId, String model, float[] query, int limit, Instant deadline) {
        requireUser(userId);
        requireModel(model);
        AgentMemoryVectorService.requireVector(query);
        if (limit < 1 || limit > 1024) throw new IllegalArgumentException("Memory search limit must be 1..1024");
        return locked(deadline, () -> {
            ensureReady(deadline);
            SearchResp response = timed(deadline).search(SearchReq.builder().collectionName(collection)
                    .annsField("embedding").metricType(IndexParam.MetricType.COSINE).topK(limit)
                    .data(List.of(new FloatVec(query.clone())))
                    .filter("user_id == {user} && embedding_model == {model} && is_active == true")
                    .filterTemplateValues(Map.of("user", userId, "model", model))
                    .outputFields(List.of("memory_version_id"))
                    .searchParams(Map.of("ef", Math.max(64, limit * 4)))
                    .consistencyLevel(ConsistencyLevel.STRONG).build());
            if (response == null || response.getSearchResults() == null)
                throw new IllegalStateException("Memory vector search response is missing");
            if (response.getSearchResults().isEmpty()) return List.of();
            return response.getSearchResults().getFirst().stream()
                    .filter(hit -> Float.isFinite(hit.getScore()) && hit.getScore() >= minimumScore)
                    .map(hit -> number(hit.getId())).toList();
        });
    }

    private void ensureReady(Instant deadline) {
        if (ready) return;
        CreateCollectionReq expected = MilvusCollectionSchemas.memory();
        expected.setCollectionName(collection);
        expected.setConsistencyLevel(ConsistencyLevel.STRONG);
        if (!Boolean.TRUE.equals(timed(deadline).hasCollection(HasCollectionReq.builder().collectionName(collection).build()))) {
            if (!initializeSchema) throw new IllegalStateException("Memory Milvus collection is missing; initialize schema explicitly");
            timed(deadline).createCollection(expected);
        }
        DescribeCollectionResp actual = timed(deadline).describeCollection(DescribeCollectionReq.builder().collectionName(collection).build());
        if (actual == null || !collection.equals(actual.getCollectionName())
                || !Boolean.FALSE.equals(actual.getAutoID()) || !Boolean.FALSE.equals(actual.getEnableDynamicField())
                || actual.getCollectionSchema() == null
                || !fieldContracts(expected.getCollectionSchema().getFieldSchemaList())
                    .equals(fieldContracts(actual.getCollectionSchema().getFieldSchemaList())))
            throw new IllegalStateException("Memory Milvus collection schema drift");
        var vectorIndex = expected.getIndexParams().getFirst();
        var indexes = timed(deadline).describeIndex(DescribeIndexReq.builder().collectionName(collection)
                .indexName(vectorIndex.getIndexName()).build());
        var index = indexes == null ? null : indexes.getIndexDescByIndexName(vectorIndex.getIndexName());
        if (index == null || !"embedding".equals(index.getFieldName())
                || index.getMetricType() != IndexParam.MetricType.COSINE
                || index.getIndexType() != IndexParam.IndexType.HNSW)
            throw new IllegalStateException("Memory Milvus vector index schema drift");
        timed(deadline).loadCollection(LoadCollectionReq.builder().collectionName(collection)
                .sync(true).timeout(remainingMillis(deadline)).build());
        remainingMillis(deadline);
        ready = true;
    }

    private JsonObject toRow(MemoryVectorDocument document) {
        MemoryProjectionRow source = document.source();
        requireUser(source.userId());
        requireModel(document.embeddingModel());
        AgentMemoryVectorService.requireVector(document.embedding());
        if (source.memoryVersionId() <= 0 || source.memoryId() <= 0 || !"LOW".equals(source.sensitivity()))
            throw new IllegalArgumentException("Invalid memory vector identity or sensitivity");
        JsonObject row = new JsonObject();
        row.addProperty("memory_version_id", source.memoryVersionId());
        row.addProperty("memory_id", source.memoryId());
        row.addProperty("user_id", source.userId());
        row.addProperty("category", source.category());
        row.addProperty("sensitivity", source.sensitivity());
        row.addProperty("content_hash", source.contentHash());
        row.addProperty("embedding_model", document.embeddingModel());
        row.addProperty("is_active", true);
        // 到期字段只是派生元数据；延期/暂停即时生效依赖 MySQL 最终校验，不能用陈旧字段拒绝延期。
        row.addProperty("expires_at_epoch", source.expiresAt() == null ? 0 : source.expiresAt().atZone(clock.getZone()).toEpochSecond());
        row.addProperty("lifecycle_epoch", 0L);
        row.addProperty("aggregate_version", source.lockVersion());
        JsonArray vector = new JsonArray(1024);
        for (float value : document.embedding()) vector.add(value);
        row.add("embedding", vector);
        return row;
    }

    private <T> T locked(Instant deadline, Supplier<T> operation) {
        boolean acquired = false;
        try {
            acquired = rpcLock.tryLock(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            if (!acquired) throw new IllegalStateException("Memory vector deadline exceeded waiting for RPC");
            remainingMillis(deadline);
            T result = operation.get();
            remainingMillis(deadline);
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Memory vector deadline interrupted", interrupted);
        } catch (RuntimeException failure) {
            if (acquired) ready = false;
            throw failure;
        } finally {
            if (acquired) rpcLock.unlock();
        }
    }

    private MilvusClientV2 timed(Instant deadline) {
        long remaining = remainingMillis(deadline);
        // SDK 默认 75 次重试会重复消费单 RPC 预算；持久状态机负责幂等重试。
        client.retryConfig(RetryConfig.builder().maxRetryTimes(1).retryOnRateLimit(false).build());
        return client.withTimeout(remaining, TimeUnit.MILLISECONDS);
    }

    private long remainingMillis(Instant deadline) {
        if (deadline == null || Thread.currentThread().isInterrupted()) throw new IllegalStateException("Memory vector deadline exceeded");
        long remaining = Duration.between(clock.instant(), deadline).toMillis();
        if (remaining <= 0) throw new IllegalStateException("Memory vector deadline exceeded");
        return remaining;
    }

    private static List<QueryResp.QueryResult> requireQueryRows(QueryResp response) {
        if (response == null || response.getQueryResults() == null)
            throw new IllegalStateException("Memory vector visibility response is missing");
        return response.getQueryResults();
    }

    private static List<String> fieldContracts(List<CreateCollectionReq.FieldSchema> fields) {
        if (fields == null) return List.of();
        return fields.stream().map(field -> String.join(":", field.getName(), String.valueOf(field.getDataType()),
                field.getDataType() == DataType.VarChar ? String.valueOf(field.getMaxLength()) : "-",
                String.valueOf(field.getDimension()), String.valueOf(field.getIsPrimaryKey()),
                String.valueOf(field.getIsPartitionKey()), String.valueOf(field.getAutoID()),
                String.valueOf(field.getIsNullable()))).sorted().toList();
    }

    private static void requireUser(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
    }

    private static void requireModel(String model) {
        if (model == null || model.isBlank() || model.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64)
            throw new IllegalArgumentException("Memory embedding model must fit 64 bytes");
    }

    private static long number(Object value) {
        if (!(value instanceof Number number)) throw new IllegalStateException("Memory Milvus identifier is not numeric");
        return number.longValue();
    }
}
