package cumt.zongzuo.community.article.projection.vector;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import io.milvus.v2.service.utility.response.DescribeAliasResp;
import io.milvus.v2.service.utility.response.ListAliasResp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SdkMilvusSchemaAdminTest {

    @Test
    void missingCollectionIsCreatedThenFullyReadBackBeforeAliasIsAccepted() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        CreateCollectionReq expected = MilvusCollectionSchemas.article();
        stubExactReadback(client, expected, MilvusCollectionSchemas.ARTICLE_ALIAS);
        when(client.hasCollection(any())).thenReturn(false);
        when(client.listAliases(any())).thenReturn(ListAliasResp.builder().alias(List.of()).build());

        new SdkMilvusSchemaAdmin(client).ensureExact(expected, MilvusCollectionSchemas.ARTICLE_ALIAS);

        verify(client).createCollection(expected);
        verify(client).createAlias(any());
    }

    @Test
    void anExistingCollectionWithSchemaDriftFailsBeforeAliasMutation() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        CreateCollectionReq expected = MilvusCollectionSchemas.article();
        when(client.hasCollection(any())).thenReturn(true);
        DescribeCollectionResp drifted = exactCollection(expected);
        drifted.setEnableDynamicField(true);
        when(client.describeCollection(any())).thenReturn(drifted);

        assertThatThrownBy(() -> new SdkMilvusSchemaAdmin(client)
                .ensureExact(expected, MilvusCollectionSchemas.ARTICLE_ALIAS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema drift");
        verify(client, never()).createAlias(any());
    }

    @Test
    void anAliasPointingAtAnotherCollectionFailsClosed() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        CreateCollectionReq expected = MilvusCollectionSchemas.article();
        stubExactReadback(client, expected, MilvusCollectionSchemas.ARTICLE_ALIAS);
        when(client.hasCollection(any())).thenReturn(true);
        when(client.listAliases(any())).thenReturn(ListAliasResp.builder()
                .alias(List.of(MilvusCollectionSchemas.ARTICLE_ALIAS)).build());
        when(client.describeAlias(any())).thenReturn(DescribeAliasResp.builder()
                .alias(MilvusCollectionSchemas.ARTICLE_ALIAS)
                .collectionName("wrong_collection")
                .build());

        assertThatThrownBy(() -> new SdkMilvusSchemaAdmin(client)
                .ensureExact(expected, MilvusCollectionSchemas.ARTICLE_ALIAS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alias drift");
    }

    private static void stubExactReadback(MilvusClientV2 client, CreateCollectionReq expected,
                                          String alias) {
        when(client.describeCollection(any())).thenReturn(exactCollection(expected));
        Map<String, IndexParam> indexes = expected.getIndexParams().stream()
                .collect(Collectors.toMap(IndexParam::getIndexName, index -> index));
        when(client.describeIndex(any())).thenAnswer(invocation -> {
            DescribeIndexReq request = invocation.getArgument(0);
            IndexParam index = indexes.get(request.getIndexName());
            Map<String, String> params = index.getExtraParams().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> String.valueOf(entry.getValue())));
            return DescribeIndexResp.builder().indexDescriptions(List.of(
                    DescribeIndexResp.IndexDesc.builder()
                            .fieldName(index.getFieldName())
                            .indexName(index.getIndexName())
                            .indexType(index.getIndexType())
                            .metricType(index.getMetricType())
                            .extraParams(params)
                            .build())).build();
        });
        when(client.describeAlias(any())).thenReturn(DescribeAliasResp.builder()
                .alias(alias).collectionName(expected.getCollectionName()).build());
    }

    private static DescribeCollectionResp exactCollection(CreateCollectionReq request) {
        return DescribeCollectionResp.builder()
                .collectionName(request.getCollectionName())
                .numOfPartitions(request.getNumPartitions() == null ? null
                        : request.getNumPartitions().longValue())
                .enableDynamicField(request.getEnableDynamicField())
                .autoID(request.getAutoID())
                .collectionSchema(request.getCollectionSchema())
                .build();
    }
}
