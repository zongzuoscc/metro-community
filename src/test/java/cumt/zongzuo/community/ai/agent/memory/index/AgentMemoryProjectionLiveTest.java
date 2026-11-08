package cumt.zongzuo.community.ai.agent.memory.index;

import cumt.zongzuo.community.ai.agent.memory.AgeMemLiveDatabaseSupport;
import cumt.zongzuo.community.ai.agent.memory.AgentMemorySafetyPolicy;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import cumt.zongzuo.community.ai.provider.EmbeddingResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实 MySQL + MyBatis + Milvus；仅 embedding 用固定向量，不能据此声称真实模型语义质量。 */
@EnabledIfEnvironmentVariable(named = "AGEMEM_LIVE_MILVUS_URI", matches = ".+")
class AgentMemoryProjectionLiveTest {
    @Test
    void committedMemoryIsImmediatelyUsableWhileProjectionUpdateAndDeletionConvergeInBackground() throws Exception {
        assumeTrue(System.getenv("AGEMEM_MYSQL_URL") != null, "Explicit isolated MySQL schema is required");
        AgeMemLiveDatabaseSupport db = new AgeMemLiveDatabaseSupport();
        UUID runId = UUID.randomUUID();
        db.seed(runId);
        String collection = "agemem_projection_test_" + UUID.randomUUID().toString().replace("-", "");
        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder()
                .uri(required("AGEMEM_LIVE_MILVUS_URI"))
                .username(required("AGEMEM_LIVE_MILVUS_USER"))
                .password(required("AGEMEM_LIVE_MILVUS_PASSWORD"))
                .connectTimeoutMs(10_000L).rpcDeadlineMs(60_000L).build());
        try {
            try {
                Clock clock = Clock.systemUTC();
                SdkMemoryVectorRepository repository = new SdkMemoryVectorRepository(client, collection, true, clock);
                List<List<String>> embeddings = new ArrayList<>();
                EmbeddingGateway embedding = command -> {
                    embeddings.add(command.inputs());
                    return new EmbeddingResult(command.inputs().stream().map(text -> vector()).toList(), "test", "agemem-live-test");
                };
                AgentMemoryVectorService vectors = new AgentMemoryVectorService(db.mapper, repository, executor(), embedding,
                        new AgentMemorySafetyPolicy(), clock, Duration.ofSeconds(10), "agemem-live-test", 32);
                AgentMemoryVectorProjectionTask worker = new AgentMemoryVectorProjectionTask(db.mapper, vectors, clock,
                        Duration.ofSeconds(90), "agemem-live-test", 32);

                var added = db.tools.add(9, runId, 1, "PREFERENCE", "回答时先给结论", "回答时先给结论");
                assertThat(added.view().content()).isEqualTo("回答时先给结论");
                assertThat(vectors.indexStatus(9)).isEqualTo(AgentMemoryVectorService.IndexStatus.PENDING);
                assertThat(embeddings).isEmpty();
                assertThat(vectors.recall(9, "回答风格", 4, deadline())).isEmpty();
                assertThat(embeddings).containsExactly(List.of("回答风格"));
                assertThat(db.mapper.projectionState(added.view().id(), 9)).isEqualTo("PENDING");

                worker.projectPending();
                assertThat(vectors.indexStatus(9)).isEqualTo(AgentMemoryVectorService.IndexStatus.CURRENT);
                assertThat(vectors.recall(9, "回答风格", 4, deadline())).singleElement()
                        .satisfies(memory -> assertThat(memory.content()).isEqualTo("回答时先给结论"));
                assertThat(vectors.recall(10, "回答风格", 4, deadline())).isEmpty();

                db.tools.update(9, runId, 2, added.view().id(), 1, "回答时分步骤解释", "回答时分步骤解释");
                // 旧向量仍在 Milvus，但 MySQL 已切换版本，召回必须立即拒绝旧正文。
                assertThat(vectors.recall(9, "回答风格", 4, deadline())).isEmpty();
                assertThat(vectors.indexStatus(9)).isEqualTo(AgentMemoryVectorService.IndexStatus.PENDING);
                worker.projectPending();
                assertThat(vectors.recall(9, "回答风格", 4, deadline())).singleElement().satisfies(memory -> {
                    assertThat(memory.version()).isEqualTo(2);
                    assertThat(memory.content()).isEqualTo("回答时分步骤解释");
                });
                vectors.deletePending(9, deadline());
                assertThat(repository.search(9, "agemem-live-test", vector(), 8, deadline()))
                        .containsExactly(db.mapper.currentVersionId(added.view().id(), 9));

                db.tools.delete(9, runId, 3, added.view().id(), 2, "忘掉原来的回答习惯");
                assertThat(vectors.recall(9, "回答风格", 4, deadline())).isEmpty();
                vectors.deletePending(9, deadline());
                assertThat(repository.search(9, "agemem-live-test", vector(), 8, deadline())).isEmpty();
                assertThat(db.jdbc.queryForList("SELECT state FROM agent_memory_projection WHERE user_id=9", String.class))
                        .containsOnly("DELETED");
            } finally {
                if (client.hasCollection(HasCollectionReq.builder().collectionName(collection).build()))
                    client.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
            }
        } finally {
            client.close();
        }
    }

    private static AiCapabilityExecutor executor() {
        return new AiCapabilityExecutor() {
            public <T> T execute(AiInvocationContext context, CheckedSupplier<T> operation) {
                try { return operation.get(); } catch (Throwable failure) { throw new IllegalStateException(failure); }
            }
            public <A,T> T execute(AiInvocationContext context, AttemptObserver<A,T> observer, AttemptOperation<A,T> operation) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static Instant deadline() { return Instant.now().plusSeconds(90); }
    private static float[] vector() { float[] vector = new float[1024]; vector[0] = 1F; return vector; }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
