package cumt.zongzuo.community.article.projection.vector;

import cumt.zongzuo.community.event.projection.registry.ProjectionTargetRegistration;
import cumt.zongzuo.community.event.projection.registry.ProjectionTargetRegistryStore;

import java.util.List;
import java.util.Objects;

public final class MilvusProjectionSchemaProvisioner {

    private static final long ARTICLE_TARGET_ID = 1001L;
    private static final long MEMORY_TARGET_ID = 1002L;

    private final MilvusSchemaAdmin schemaAdmin;
    private final ProjectionTargetRegistryStore registryStore;
    private final String modelName;
    private final String modelDigest;
    private final String operatorIdentity;

    public MilvusProjectionSchemaProvisioner(MilvusSchemaAdmin schemaAdmin,
                                             ProjectionTargetRegistryStore registryStore,
                                             String modelName,
                                             String modelDigest,
                                             String operatorIdentity) {
        this.schemaAdmin = Objects.requireNonNull(schemaAdmin, "schemaAdmin");
        this.registryStore = Objects.requireNonNull(registryStore, "registryStore");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.modelDigest = Objects.requireNonNull(modelDigest, "modelDigest");
        this.operatorIdentity = Objects.requireNonNull(operatorIdentity, "operatorIdentity");
    }

    public void provision() {
        var article = MilvusCollectionSchemas.article();
        var memory = MilvusCollectionSchemas.memory();
        schemaAdmin.ensureExact(article, MilvusCollectionSchemas.ARTICLE_ALIAS);
        schemaAdmin.ensureExact(memory, MilvusCollectionSchemas.MEMORY_ALIAS);

        registryStore.registerSchemaOnly(List.of(
                new ProjectionTargetRegistration(ARTICLE_TARGET_ID, "MILVUS_ARTICLE",
                        MilvusCollectionSchemas.ARTICLE_COLLECTION, MilvusCollectionSchemas.ARTICLE_ALIAS,
                        MilvusCollectionSchemas.fingerprint(article), modelName, modelDigest,
                        MilvusCollectionSchemas.EMBEDDING_DIMENSION, 1, "ARTICLE", operatorIdentity),
                new ProjectionTargetRegistration(MEMORY_TARGET_ID, "MILVUS_MEMORY",
                        MilvusCollectionSchemas.MEMORY_COLLECTION, MilvusCollectionSchemas.MEMORY_ALIAS,
                        MilvusCollectionSchemas.fingerprint(memory), modelName, modelDigest,
                        MilvusCollectionSchemas.EMBEDDING_DIMENSION, 1, "MEMORY", operatorIdentity)));
    }
}
