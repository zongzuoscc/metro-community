package cumt.zongzuo.community.article.projection.vector;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.event.projection.registry.ProjectionTargetRegistryStore;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilvusProjectionSchemaProvisionerIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private ProjectionTargetRegistryStore registryStore;

    @BeforeEach
    void cleanTargets() {
        jdbcTemplate.update("DELETE FROM projection_entity_manifest");
        jdbcTemplate.update("DELETE FROM projection_rebuild_item");
        jdbcTemplate.update("DELETE FROM projection_rebuild_job");
        jdbcTemplate.update("DELETE FROM projection_target_registry");
    }

    @Test
    void exactExternalSchemasAreRegisteredAsIdempotentSchemaOnlyTargets() {
        RecordingAdmin admin = new RecordingAdmin(false);
        MilvusProjectionSchemaProvisioner provisioner = new MilvusProjectionSchemaProvisioner(
                admin, registryStore, "metro-bge-m3:790764642607", "a".repeat(64), "schema-test");

        provisioner.provision();
        provisioner.provision();

        assertThat(admin.requests).extracting(request -> request.schema().getCollectionName())
                .containsExactly(
                        MilvusCollectionSchemas.ARTICLE_COLLECTION,
                        MilvusCollectionSchemas.MEMORY_COLLECTION,
                        MilvusCollectionSchemas.ARTICLE_COLLECTION,
                        MilvusCollectionSchemas.MEMORY_COLLECTION);
        assertThat(jdbcTemplate.queryForList("""
                SELECT CONCAT(id,':',kind,':',physical_name,':',read_alias,':',state,':',
                              IFNULL(consumer_name,'NULL'),':',required_for_retention,':',dimension)
                FROM projection_target_registry ORDER BY id
                """, String.class)).containsExactly(
                "1001:MILVUS_ARTICLE:metro_article_chunks_bgem3_v1:metro_article_chunks_read:SCHEMA_ONLY:NULL:0:1024",
                "1002:MILVUS_MEMORY:metro_user_memories_bgem3_v1:metro_user_memories_read:SCHEMA_ONLY:NULL:0:1024");
    }

    @Test
    void anExternalSchemaFailureLeavesNoPartiallyAuthorizedRegistryTarget() {
        MilvusProjectionSchemaProvisioner provisioner = new MilvusProjectionSchemaProvisioner(
                new RecordingAdmin(true), registryStore,
                "metro-bge-m3:790764642607", "b".repeat(64), "schema-test");

        assertThatThrownBy(provisioner::provision)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("memory schema rejected");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM projection_target_registry", Integer.class)).isZero();
    }

    private static final class RecordingAdmin implements MilvusSchemaAdmin {
        private final List<SchemaRequest> requests = new ArrayList<>();
        private final boolean rejectMemory;

        private RecordingAdmin(boolean rejectMemory) {
            this.rejectMemory = rejectMemory;
        }

        @Override
        public void ensureExact(CreateCollectionReq schema, String readAlias) {
            requests.add(new SchemaRequest(schema, readAlias));
            if (rejectMemory && schema.getCollectionName().equals(MilvusCollectionSchemas.MEMORY_COLLECTION)) {
                throw new IllegalStateException("memory schema rejected");
            }
        }

        private record SchemaRequest(CreateCollectionReq schema, String alias) {
        }
    }
}
