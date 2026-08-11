package cumt.zongzuo.community.article.projection.vector;

import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MilvusCollectionSchemasTest {

    @Test
    void articleSchemaFreezesEveryFieldAndIndexWithoutDynamicOrAutoIds() {
        CreateCollectionReq request = MilvusCollectionSchemas.article();

        assertThat(request.getCollectionName()).isEqualTo("metro_article_chunks_bgem3_v1");
        assertThat(request.getEnableDynamicField()).isFalse();
        assertThat(request.getAutoID()).isFalse();
        assertThat(fieldContracts(request)).containsExactly(
                "chunk_id:Int64:null:null:true:false:false",
                "embedding:FloatVector:null:1024:false:false:false",
                "article_id:Int64:null:null:false:false:false",
                "revision_id:Int64:null:null:false:false:false",
                "author_id:Int64:null:null:false:false:false",
                "chunk_no:Int32:null:null:false:false:false",
                "content_hash:VarChar:64:null:false:false:false",
                "is_active:Bool:null:null:false:false:false",
                "published_at_epoch:Int64:null:null:false:false:false",
                "language:VarChar:16:null:false:false:false",
                "embedding_model:VarChar:64:null:false:false:false",
                "parser_version:VarChar:32:null:false:false:false",
                "parser_generation:Int64:null:null:false:false:false",
                "lifecycle_epoch:Int64:null:null:false:false:false",
                "aggregate_version:Int64:null:null:false:false:false",
                "source_aggregate_version:Int64:null:null:false:false:false");

        assertThat(indexContracts(request)).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("idx_embedding_hnsw", "embedding:HNSW:COSINE:{M=16, efConstruction=256}"),
                Map.entry("idx_article_active_bitmap", "is_active:BITMAP:INVALID:{}"),
                Map.entry("idx_article_article_id_inverted", "article_id:INVERTED:INVALID:{}"),
                Map.entry("idx_article_revision_id_inverted", "revision_id:INVERTED:INVALID:{}"),
                Map.entry("idx_article_author_id_inverted", "author_id:INVERTED:INVALID:{}"),
                Map.entry("idx_article_language_inverted", "language:INVERTED:INVALID:{}"),
                Map.entry("idx_article_published_at_epoch_inverted", "published_at_epoch:INVERTED:INVALID:{}"),
                Map.entry("idx_article_embedding_model_inverted", "embedding_model:INVERTED:INVALID:{}")));
    }

    @Test
    void memorySchemaUsesAnExplicitSixtyFourPartitionTenantKey() {
        CreateCollectionReq request = MilvusCollectionSchemas.memory();

        assertThat(request.getCollectionName()).isEqualTo("metro_user_memories_bgem3_v1");
        assertThat(request.getEnableDynamicField()).isFalse();
        assertThat(request.getAutoID()).isFalse();
        assertThat(request.getNumPartitions()).isEqualTo(64);
        assertThat(fieldContracts(request)).containsExactly(
                "memory_version_id:Int64:null:null:true:false:false",
                "embedding:FloatVector:null:1024:false:false:false",
                "memory_id:Int64:null:null:false:false:false",
                "user_id:Int64:null:null:false:true:false",
                "category:VarChar:24:null:false:false:false",
                "sensitivity:VarChar:16:null:false:false:false",
                "is_active:Bool:null:null:false:false:false",
                "expires_at_epoch:Int64:null:null:false:false:false",
                "content_hash:VarChar:64:null:false:false:false",
                "embedding_model:VarChar:64:null:false:false:false",
                "lifecycle_epoch:Int64:null:null:false:false:false",
                "aggregate_version:Int64:null:null:false:false:false");
    }

    private static List<String> fieldContracts(CreateCollectionReq request) {
        return request.getCollectionSchema().getFieldSchemaList().stream()
                .map(field -> String.join(":", field.getName(), field.getDataType().name(),
                        field.getDataType() == io.milvus.v2.common.DataType.VarChar
                                ? String.valueOf(field.getMaxLength()) : "null",
                        String.valueOf(field.getDimension()),
                        String.valueOf(field.getIsPrimaryKey()), String.valueOf(field.getIsPartitionKey()),
                        String.valueOf(field.getIsNullable())))
                .toList();
    }

    private static Map<String, String> indexContracts(CreateCollectionReq request) {
        return request.getIndexParams().stream().collect(Collectors.toMap(
                IndexParam::getIndexName,
                index -> index.getFieldName() + ":" + index.getIndexType().name() + ":"
                        + index.getMetricType().name() + ":" + index.getExtraParams()));
    }
}
