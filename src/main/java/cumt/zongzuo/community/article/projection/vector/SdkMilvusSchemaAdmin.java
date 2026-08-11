package cumt.zongzuo.community.article.projection.vector;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import io.milvus.v2.service.utility.request.CreateAliasReq;
import io.milvus.v2.service.utility.request.DescribeAliasReq;
import io.milvus.v2.service.utility.request.ListAliasesReq;
import io.milvus.v2.service.utility.response.DescribeAliasResp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SdkMilvusSchemaAdmin implements MilvusSchemaAdmin {

    private final MilvusClientV2 client;

    public SdkMilvusSchemaAdmin(MilvusClientV2 client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void ensureExact(CreateCollectionReq expected, String readAlias) {
        Objects.requireNonNull(expected, "expected");
        if (readAlias == null || readAlias.isBlank()) {
            throw new IllegalArgumentException("readAlias must not be blank");
        }
        String collection = expected.getCollectionName();
        if (!client.hasCollection(HasCollectionReq.builder().collectionName(collection).build())) {
            client.createCollection(expected);
        }
        verifyCollection(expected, client.describeCollection(DescribeCollectionReq.builder()
                .collectionName(collection).build()));
        for (IndexParam index : expected.getIndexParams()) {
            verifyIndex(index, client.describeIndex(DescribeIndexReq.builder()
                    .collectionName(collection)
                    .indexName(index.getIndexName())
                    .build()));
        }

        List<String> aliases = client.listAliases(ListAliasesReq.builder()
                .collectionName(collection).build()).getAlias();
        if (aliases == null || !aliases.contains(readAlias)) {
            client.createAlias(CreateAliasReq.builder()
                    .collectionName(collection)
                    .alias(readAlias)
                    .build());
        }
        DescribeAliasResp alias = client.describeAlias(DescribeAliasReq.builder().alias(readAlias).build());
        if (!readAlias.equals(alias.getAlias()) || !collection.equals(alias.getCollectionName())) {
            throw new IllegalStateException("Milvus alias drift: " + readAlias);
        }
    }

    private static void verifyCollection(CreateCollectionReq expected, DescribeCollectionResp actual) {
        if (actual == null
                || !expected.getCollectionName().equals(actual.getCollectionName())
                || !Objects.equals(expected.getEnableDynamicField(), actual.getEnableDynamicField())
                || !Objects.equals(expected.getAutoID(), actual.getAutoID())
                || expected.getNumPartitions() != null
                && !Objects.equals(expected.getNumPartitions().longValue(), actual.getNumOfPartitions())
                || !fieldContracts(expected.getCollectionSchema().getFieldSchemaList())
                .equals(fieldContracts(actual.getCollectionSchema().getFieldSchemaList()))) {
            throw new IllegalStateException("Milvus collection schema drift: "
                    + expected.getCollectionName());
        }
    }

    private static void verifyIndex(IndexParam expected, DescribeIndexResp response) {
        DescribeIndexResp.IndexDesc actual = response == null
                ? null : response.getIndexDescByIndexName(expected.getIndexName());
        Map<String, String> expectedParams = new LinkedHashMap<>();
        expected.getExtraParams().forEach((key, value) -> expectedParams.put(key, String.valueOf(value)));
        if (actual == null
                || !expected.getFieldName().equals(actual.getFieldName())
                || expected.getIndexType() != actual.getIndexType()
                || expected.getMetricType() != actual.getMetricType()
                || !expectedParams.equals(actual.getExtraParams())) {
            throw new IllegalStateException("Milvus index schema drift: " + expected.getIndexName());
        }
    }

    private static List<String> fieldContracts(List<CreateCollectionReq.FieldSchema> fields) {
        if (fields == null) {
            return List.of();
        }
        return fields.stream().map(field -> String.join(":",
                field.getName(),
                field.getDataType().name(),
                field.getDataType() == DataType.VarChar ? String.valueOf(field.getMaxLength()) : "-",
                String.valueOf(field.getDimension()),
                String.valueOf(field.getIsPrimaryKey()),
                String.valueOf(field.getIsPartitionKey()),
                String.valueOf(field.getAutoID()),
                String.valueOf(field.getIsNullable()),
                String.valueOf(field.getDefaultValue()))).toList();
    }
}
