package cumt.zongzuo.community.article.projection.vector;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class MilvusCollectionContractMilvusIT {

    private static MilvusClientV2 client;

    @BeforeAll
    static void connect() {
        client = new MilvusClientV2(ConnectConfig.builder()
                .uri(System.getProperty("milvus.uri", "http://127.0.0.1:29530"))
                .username(System.getProperty("milvus.username", "root"))
                .password(System.getProperty("milvus.password", "Milvus"))
                .connectTimeoutMs(10_000)
                .rpcDeadlineMs(60_000)
                .build());
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void lockedServerCreatesAndReadsBackBothExactSchemasIdempotently() {
        assertThat(client.checkHealth().getIsHealthy()).isTrue();
        assertThat(client.getServerVersion()).startsWith("2.6.20");

        SdkMilvusSchemaAdmin admin = new SdkMilvusSchemaAdmin(client);
        admin.ensureExact(MilvusCollectionSchemas.article(), MilvusCollectionSchemas.ARTICLE_ALIAS);
        admin.ensureExact(MilvusCollectionSchemas.article(), MilvusCollectionSchemas.ARTICLE_ALIAS);
        admin.ensureExact(MilvusCollectionSchemas.memory(), MilvusCollectionSchemas.MEMORY_ALIAS);
        admin.ensureExact(MilvusCollectionSchemas.memory(), MilvusCollectionSchemas.MEMORY_ALIAS);
    }

    @Test
    void articleRepositoryUpsertsSearchesAndStronglyVerifiesDeletes() {
        SdkMilvusSchemaAdmin admin = new SdkMilvusSchemaAdmin(client);
        admin.ensureExact(MilvusCollectionSchemas.article(), MilvusCollectionSchemas.ARTICLE_ALIAS);

        ArticleVectorRepository repository = new SdkArticleVectorRepository(client);
        long firstId = 9_100_001L;
        long secondId = 9_100_002L;
        repository.deleteByChunkIds(MilvusCollectionSchemas.ARTICLE_COLLECTION,
                List.of(firstId, secondId));

        assertThat(repository.upsert(MilvusCollectionSchemas.ARTICLE_COLLECTION, List.of(
                document(firstId, 701L, unitVector(0)),
                document(secondId, 702L, unitVector(1))))).isEqualTo(2L);
        assertThat(repository.listChunkIdsByArticle(MilvusCollectionSchemas.ARTICLE_COLLECTION, 701L))
                .containsExactly(firstId);
        assertThat(repository.listChunkIdsByArticle(MilvusCollectionSchemas.ARTICLE_COLLECTION, 702L))
                .containsExactly(secondId);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(repository.searchActive(MilvusCollectionSchemas.ARTICLE_ALIAS,
                        unitVector(0), 10, "bge-m3", 1L))
                        .extracting(ArticleVectorHit::chunkId)
                        .contains(firstId));

        assertThat(repository.deleteByChunkIds(MilvusCollectionSchemas.ARTICLE_COLLECTION,
                List.of(firstId))).isEqualTo(1L);
        repository.assertDeletedStrong(MilvusCollectionSchemas.ARTICLE_COLLECTION, List.of(firstId));
    }

    private static ArticleVectorDocument document(long chunkId, long articleId, float[] embedding) {
        return new ArticleVectorDocument(chunkId, embedding, articleId, articleId + 1_000,
                42L, 0, "a".repeat(64), true, 1_700_000_000L, "zh-CN", "bge-m3",
                "markdown-v1", 1L, 1L, 1L, 1L);
    }

    private static float[] unitVector(int position) {
        float[] vector = new float[MilvusCollectionSchemas.EMBEDDING_DIMENSION];
        vector[position] = 1.0F;
        return vector;
    }
}
