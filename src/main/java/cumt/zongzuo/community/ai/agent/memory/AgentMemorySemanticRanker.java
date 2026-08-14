package cumt.zongzuo.community.ai.agent.memory;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.EmbeddingCommand;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import cumt.zongzuo.community.ai.provider.EmbeddingResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通过 Embedding 计算问题与长期记忆的语义接近程度。
 *
 * <p>MySQL 中的不可变记忆版本仍是唯一事实源；向量只是可丢弃的派生缓存。相同
 * memoryId/version 的文本不会重复计算，进程重启后按需重建。这样不需要为了本次收尾
 * 再引入一套异步向量写入状态机，同时模型不可用时上层仍可退回词法召回。</p>
 */
@Service
@ConditionalOnProperty(name = {
        "metro.ai.enabled", "metro.ai.embedding.enabled", "metro.ai.memory.semantic-enabled"
}, havingValue = "true")
public class AgentMemorySemanticRanker {

    private static final int MAX_CACHE_ENTRIES = 2_048;
    private static final int MAX_EMBEDDED_CONTENT_CHARACTERS = 800;

    private final AiCapabilityExecutor executor;
    private final EmbeddingGateway embedding;
    private final Clock clock;
    private final Duration timeout;
    private final String expectedModel;
    private final Map<MemoryVersionKey, float[]> memoryVectors = new ConcurrentHashMap<>();

    public AgentMemorySemanticRanker(AiCapabilityExecutor executor,
                                     EmbeddingGateway embedding,
                                     Clock clock,
                                     @Value("${metro.ai.embedding.timeout:PT45S}") Duration timeout,
                                     @Value("${metro.ai.embedding.model:bge-m3}")
                                     String expectedModel) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.expectedModel = requireText(expectedModel, "expectedModel");
    }

    public List<AgentMemorySemanticScore> rank(long userId, String query,
                                               List<AgentMemoryView> candidates) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<AgentMemoryView> missing = candidates.stream()
                .filter(memory -> !memoryVectors.containsKey(key(userId, memory))).toList();
        List<String> inputs = new ArrayList<>(missing.size() + 1);
        inputs.add(query.strip());
        missing.stream().map(memory -> bounded(memory.content())).forEach(inputs::add);
        int characters = inputs.stream().mapToInt(String::length).sum();
        EmbeddingResult result = executor.execute(new AiInvocationContext(
                        AiCapability.EMBEDDING, userId, "memory-semantic-recall", characters,
                        clock.instant().plus(timeout), false),
                () -> embedding.embed(new EmbeddingCommand(AiCapability.EMBEDDING, inputs)));
        if (!expectedModel.equals(result.model()) || result.vectors().size() != inputs.size()) {
            throw new IllegalStateException("Memory embedding result is incompatible");
        }
        List<float[]> vectors = result.vectors();
        float[] queryVector = vectors.getFirst();
        for (int index = 0; index < missing.size(); index++) {
            cache(key(userId, missing.get(index)), vectors.get(index + 1));
        }
        return candidates.stream().map(memory -> new AgentMemorySemanticScore(memory.id(),
                        cosine(queryVector, requiredVector(userId, memory))))
                .sorted(Comparator.comparingDouble(AgentMemorySemanticScore::score).reversed()
                        .thenComparingLong(AgentMemorySemanticScore::memoryId))
                .toList();
    }

    private void cache(MemoryVersionKey key, float[] vector) {
        if (memoryVectors.size() >= MAX_CACHE_ENTRIES) {
            // 记忆向量是可重建缓存；达到硬上限时整体清空比无界增长或复杂淘汰锁更安全。
            memoryVectors.clear();
        }
        memoryVectors.put(key, vector.clone());
    }

    private float[] requiredVector(long userId, AgentMemoryView memory) {
        float[] vector = memoryVectors.get(key(userId, memory));
        if (vector == null) throw new IllegalStateException("Memory vector cache is incomplete");
        return vector;
    }

    private static MemoryVersionKey key(long userId, AgentMemoryView memory) {
        return new MemoryVersionKey(userId, memory.id(), memory.version());
    }

    private static String bounded(String content) {
        String value = requireText(content, "memory content");
        return value.substring(0, Math.min(value.length(), MAX_EMBEDDED_CONTENT_CHARACTERS));
    }

    private static double cosine(float[] left, float[] right) {
        if (left.length == 0 || left.length != right.length) {
            throw new IllegalStateException("Memory vectors have incompatible dimensions");
        }
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int index = 0; index < left.length; index++) {
            float l = left[index];
            float r = right[index];
            if (!Float.isFinite(l) || !Float.isFinite(r)) {
                throw new IllegalStateException("Memory vectors must be finite");
            }
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0D || rightNorm == 0D) return 0D;
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is blank");
        return value.strip();
    }

    private record MemoryVersionKey(long userId, long memoryId, long version) {
    }
}
