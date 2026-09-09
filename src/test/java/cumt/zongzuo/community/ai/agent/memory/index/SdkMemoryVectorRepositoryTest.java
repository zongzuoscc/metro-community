package cumt.zongzuo.community.ai.agent.memory.index;

import cumt.zongzuo.community.article.projection.vector.MilvusCollectionSchemas;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.response.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SdkMemoryVectorRepositoryTest {
    private final MilvusClientV2 client = mock(MilvusClientV2.class);
    private final Instant now = Instant.parse("2026-09-09T00:00:00Z");
    private final Instant deadline = now.plusSeconds(20);

    @Test
    void upsertUsesImmutableVersionPrimaryKeyAndRequiresStrongMatchingReadback() {
        ready();
        when(client.upsert(any())).thenReturn(UpsertResp.builder().upsertCnt(1L).build());
        when(client.query(any())).thenReturn(query(Map.of("memory_version_id", 11L, "user_id", 7L,
                "embedding_model", "bge-m3", "content_hash", "hash", "is_active", true)));
        repository().upsertAndVerify(List.of(document()), deadline);
        ArgumentCaptor<UpsertReq> upsert = ArgumentCaptor.forClass(UpsertReq.class);
        verify(client).upsert(upsert.capture());
        assertThat(upsert.getValue().getData().getFirst().get("memory_version_id").getAsLong()).isEqualTo(11);
        ArgumentCaptor<QueryReq> read = ArgumentCaptor.forClass(QueryReq.class);
        verify(client).query(read.capture());
        assertThat(read.getValue().getConsistencyLevel()).isEqualTo(ConsistencyLevel.STRONG);
        verify(client, atLeastOnce()).withTimeout(longThat(ms -> ms > 0 && ms <= 20000), eq(TimeUnit.MILLISECONDS));
        // SDK 默认会重试 75 次，不能让每次重试重新消耗完整 deadline。
        verify(client, atLeastOnce()).retryConfig(argThat(config -> config.getMaxRetryTimes() == 1));
    }

    @Test
    void missingOrWrongModelReadbackCannotCertifyProjection() {
        ready();
        when(client.upsert(any())).thenReturn(UpsertResp.builder().upsertCnt(1L).build());
        when(client.query(any())).thenReturn(QueryResp.builder().queryResults(List.of()).build());
        assertThatThrownBy(() -> repository().upsertAndVerify(List.of(document()), deadline))
                .hasMessageContaining("visible");
        when(client.query(any())).thenReturn(query(Map.of("memory_version_id", 11L, "user_id", 7L,
                "embedding_model", "old-model", "content_hash", "hash", "is_active", true)));
        assertThatThrownBy(() -> repository().upsertAndVerify(List.of(document()), deadline))
                .hasMessageContaining("visible");
    }

    @Test
    void searchIsStrongAndBindsUserModelAndActiveFilter() {
        ready();
        when(client.search(any())).thenReturn(SearchResp.builder().searchResults(List.of(List.of(
                SearchResp.SearchResult.builder().id(11L).score(.9F).entity(Map.of()).build()))).build());
        assertThat(repository().search(7, "model\"quoted", vector(), 8, deadline)).containsExactly(11L);
        ArgumentCaptor<SearchReq> request = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(request.capture());
        assertThat(request.getValue().getConsistencyLevel()).isEqualTo(ConsistencyLevel.STRONG);
        assertThat(request.getValue().getFilter()).contains("user_id == {user}", "embedding_model == {model}", "is_active == true");
        assertThat(request.getValue().getFilterTemplateValues()).containsEntry("user", 7L).containsEntry("model", "model\"quoted");
    }

    @Test
    void unrelatedAndNonFiniteScoresCannotBecomeRecallCandidates() {
        ready();
        when(client.search(any())).thenReturn(SearchResp.builder().searchResults(List.of(List.of(
                SearchResp.SearchResult.builder().id(11L).score(.9F).entity(Map.of()).build(),
                SearchResp.SearchResult.builder().id(12L).score(.1F).entity(Map.of()).build(),
                SearchResp.SearchResult.builder().id(13L).score(Float.NaN).entity(Map.of()).build()))).build());
        assertThat(repository().search(7, "bge-m3", vector(), 8, deadline)).containsExactly(11L);
    }

    @Test
    void deletionIsOwnerScopedAndAbsenceIsVerifiedStrongly() {
        ready();
        when(client.delete(any())).thenReturn(DeleteResp.builder().deleteCnt(1L).build());
        when(client.query(any())).thenReturn(QueryResp.builder().queryResults(List.of()).build());
        repository().deleteAndVerify(7, List.of(11L), deadline);
        ArgumentCaptor<DeleteReq> request = ArgumentCaptor.forClass(DeleteReq.class);
        verify(client).delete(request.capture());
        assertThat(request.getValue().getFilter()).contains("user_id == 7", "11");
        ArgumentCaptor<QueryReq> read = ArgumentCaptor.forClass(QueryReq.class);
        verify(client).query(read.capture());
        assertThat(read.getValue().getConsistencyLevel()).isEqualTo(ConsistencyLevel.STRONG);
    }

    @Test
    void incompatibleSchemaFailsBeforeVectorOperationsAndNeverCreatesByDefault() {
        ready();
        CreateCollectionReq expected = MilvusCollectionSchemas.memory();
        expected.getCollectionSchema().getFieldSchemaList().stream()
                .filter(field -> field.getName().equals("embedding")).findFirst().orElseThrow().setDimension(768);
        when(client.describeCollection(any())).thenReturn(DescribeCollectionResp.builder()
                .collectionName("memory_test").autoID(false).enableDynamicField(false)
                .collectionSchema(expected.getCollectionSchema()).build());
        assertThatThrownBy(() -> repository().search(7, "bge-m3", vector(), 8, deadline))
                .hasMessageContaining("schema");
        verify(client, never()).search(any());
        verify(client, never()).createCollection(any());
    }

    @Test
    void expiredDeadlineCannotStartAnyRpc() {
        assertThatThrownBy(() -> repository().search(7, "bge-m3", vector(), 8, now))
                .hasMessageContaining("deadline");
        verifyNoInteractions(client);
    }

    private void ready() {
        when(client.withTimeout(anyLong(), any())).thenReturn(client);
        when(client.hasCollection(any())).thenReturn(true);
        CreateCollectionReq expected = MilvusCollectionSchemas.memory();
        when(client.describeCollection(any())).thenReturn(DescribeCollectionResp.builder()
                .collectionName("memory_test").autoID(false).enableDynamicField(false)
                .collectionSchema(expected.getCollectionSchema()).build());
        var index = expected.getIndexParams().getFirst();
        when(client.describeIndex(any())).thenReturn(DescribeIndexResp.builder().indexDescriptions(List.of(
                DescribeIndexResp.IndexDesc.builder().fieldName("embedding").indexName(index.getIndexName())
                        .indexType(index.getIndexType()).metricType(index.getMetricType()).build())).build());
    }
    private SdkMemoryVectorRepository repository() {
        return new SdkMemoryVectorRepository(client, "memory_test", false, Clock.fixed(now, ZoneOffset.UTC));
    }
    private static QueryResp query(Map<String,Object> row) {
        return QueryResp.builder().queryResults(List.of(QueryResp.QueryResult.builder().entity(row).build())).build();
    }
    private static MemoryVectorDocument document() {
        return new MemoryVectorDocument(new MemoryProjectionRow(11, 7, 1, "PREFERENCE", "LOW", "喜欢短答", "hash", null, "PENDING", 0), "bge-m3", vector());
    }
    private static float[] vector() { float[] result = new float[1024]; result[0] = 1; return result; }
}
