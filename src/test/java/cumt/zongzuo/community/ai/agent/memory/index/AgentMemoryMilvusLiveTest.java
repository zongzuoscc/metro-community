package cumt.zongzuo.community.ai.agent.memory.index;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式启用的真实 Milvus 协议测试；每次只创建并删除本次随机测试 Collection。 */
@EnabledIfEnvironmentVariable(named = "AGEMEM_LIVE_MILVUS_URI", matches = ".+")
class AgentMemoryMilvusLiveTest {
    @Test
    void stronglyVisibleUpsertSearchAndDeletionKeepOwnersAndModelsIsolated() {
        String collection = "agemem_test_" + UUID.randomUUID().toString().replace("-", "");
        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder()
                .uri(required("AGEMEM_LIVE_MILVUS_URI"))
                .username(required("AGEMEM_LIVE_MILVUS_USER"))
                .password(required("AGEMEM_LIVE_MILVUS_PASSWORD"))
                .connectTimeoutMs(10_000L).rpcDeadlineMs(60_000L).build());
        try {
            SdkMemoryVectorRepository repository = new SdkMemoryVectorRepository(client, collection, true,
                    Clock.systemUTC(), 0.35D);
            try {
                Instant deadline = Instant.now().plusSeconds(90);
                repository.upsertAndVerify(List.of(document(101, 7, "bge-m3"), document(102, 8, "bge-m3"),
                        document(103, 7, "different-model")), deadline);
                assertThat(repository.search(7, "bge-m3", vector(), 8, deadline)).containsExactly(101L);
                assertThat(repository.search(8, "bge-m3", vector(), 8, deadline)).containsExactly(102L);
                assertThat(repository.search(7, "different-model", vector(), 8, deadline)).containsExactly(103L);

                // 删除参数即使包含另一个用户的版本，也只能操作服务端绑定所有者的向量。
                repository.deleteAndVerify(7, List.of(101L, 102L), deadline);
                assertThat(repository.search(7, "bge-m3", vector(), 8, deadline)).isEmpty();
                assertThat(repository.search(8, "bge-m3", vector(), 8, deadline)).containsExactly(102L);
                assertThat(repository.search(7, "different-model", vector(), 8, deadline)).containsExactly(103L);
                repository.deleteAndVerify(7, List.of(101L), deadline);
            } finally {
                if (client.hasCollection(HasCollectionReq.builder().collectionName(collection).build()))
                    client.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
            }
        } finally {
            client.close();
        }
    }

    private static MemoryVectorDocument document(long versionId, long userId, String model) {
        return new MemoryVectorDocument(new MemoryProjectionRow(versionId, userId, versionId, "PREFERENCE", "LOW",
                "偏好简短回答", "hash" + versionId, null, "PENDING", 0), model, vector());
    }

    private static float[] vector() {
        float[] vector = new float[1024];
        vector[0] = 1F;
        return vector;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
