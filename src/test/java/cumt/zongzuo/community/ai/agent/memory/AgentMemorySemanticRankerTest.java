package cumt.zongzuo.community.ai.agent.memory;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import cumt.zongzuo.community.ai.provider.EmbeddingResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMemorySemanticRankerTest {

    @Test
    void ranksParaphrasedMemoryByVectorSimilarityWithoutRequiringSharedWords() {
        AgentMemoryView concise = memory(11L, "用户偏好简短直接的回答");
        AgentMemoryView verbose = memory(12L, "用户喜欢完整介绍旅行路线");
        EmbeddingGateway embedding = command -> new EmbeddingResult(List.of(
                new float[]{1F, 0F},
                new float[]{.95F, .05F},
                new float[]{0F, 1F}), "test", "bge-m3");
        AgentMemorySemanticRanker ranker = new AgentMemorySemanticRanker(
                new DirectExecutor(), embedding,
                Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(5), "bge-m3");

        List<AgentMemorySemanticScore> scores = ranker.rank(
                7L, "请言简意赅地回答", List.of(concise, verbose));

        assertThat(scores).extracting(AgentMemorySemanticScore::memoryId)
                .containsExactly(11L, 12L);
        assertThat(scores.getFirst().score()).isGreaterThan(scores.getLast().score());
    }

    @Test
    void cachedMemoryVectorsAreNotEmbeddedAgainForTheSameImmutableVersion() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        EmbeddingGateway embedding = command -> {
            calls.incrementAndGet();
            return new EmbeddingResult(command.inputs().stream()
                    .map(ignored -> new float[]{1F, 0F}).toList(), "test", "bge-m3");
        };
        AgentMemorySemanticRanker ranker = new AgentMemorySemanticRanker(
                new DirectExecutor(), embedding,
                Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(5), "bge-m3");
        AgentMemoryView memory = memory(21L, "用户正在准备 Java 后端面试");

        ranker.rank(8L, "面试准备", List.of(memory));
        ranker.rank(8L, "怎么复习", List.of(memory));

        assertThat(calls).hasValue(2);
    }

    private static AgentMemoryView memory(long id, String content) {
        return new AgentMemoryView(id, "PREFERENCE", content, 1L,
                "ACTIVE", null, "MANUAL");
    }

    private static final class DirectExecutor implements AiCapabilityExecutor {
        @Override
        public <T> T execute(AiInvocationContext context, CheckedSupplier<T> operation) {
            assertThat(context.capability()).isEqualTo(AiCapability.EMBEDDING);
            try {
                return operation.get();
            } catch (Throwable error) {
                throw new RuntimeException(error);
            }
        }

        @Override
        public <A, T> T execute(AiInvocationContext context, AttemptObserver<A, T> observer,
                                AttemptOperation<A, T> operation) {
            throw new UnsupportedOperationException();
        }
    }
}
