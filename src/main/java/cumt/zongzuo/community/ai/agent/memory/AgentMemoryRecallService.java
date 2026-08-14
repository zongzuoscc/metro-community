package cumt.zongzuo.community.ai.agent.memory;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 使用确定性词法排序召回当前用户已启用的记忆，并对候选数和返回数设置上限。
 * 该服务不负责搜索原始历史消息，两类个人上下文保持独立可控。
 */
@Service
public class AgentMemoryRecallService {

    private final AgentMemoryMapper mapper;
    private final ObjectProvider<AgentMemorySemanticRanker> semanticRanker;

    public AgentMemoryRecallService(AgentMemoryMapper mapper,
                                    ObjectProvider<AgentMemorySemanticRanker> semanticRanker) {
        this.mapper = mapper;
        this.semanticRanker = semanticRanker;
    }

    /**
     * 只返回归属请求用户、状态为 ACTIVE 且未过期的记忆。
     * 用户关闭记忆开关时直接返回空集合，不会把个人数据继续放入模型提示词。
     */
    public List<AgentMemoryView> recall(long userId, String query, int limit) {
        if (limit < 1 || limit > 16) throw new IllegalArgumentException("Invalid memory limit");
        mapper.ensureSetting(userId);
        if (!Boolean.TRUE.equals(mapper.enabled(userId))) return List.of();
        Set<String> terms = terms(query);
        List<AgentMemoryView> candidates = mapper.listActive(userId, 100);
        java.util.Map<Long, Double> semanticScores = semanticScores(userId, query, candidates);
        return candidates.stream()
                .sorted(Comparator.comparingDouble((AgentMemoryView memory) ->
                                combinedScore(memory, terms, semanticScores)).reversed()
                        .thenComparing(AgentMemoryView::id, Comparator.reverseOrder()))
                .filter(memory -> terms.isEmpty() || score(memory, terms) > 0
                        || semanticScores.getOrDefault(memory.id(), -1D) >= 0.35D
                        || query.contains("\u8bb0\u5f97") || query.toLowerCase(Locale.ROOT).contains("remember"))
                .limit(limit).toList();
    }

    public List<AgentMemoryView> list(long userId) {
        mapper.ensureSetting(userId);
        return mapper.listActive(userId, 100);
    }

    public AgentMemoryView find(long userId, long memoryId) {
        return mapper.find(memoryId, userId);
    }

    private static int score(AgentMemoryView memory, Set<String> terms) {
        String content = memory.content().toLowerCase(Locale.ROOT);
        // 较长的词片段具有更强区分度，避免两个常见汉字就把无关记忆排到前面。
        return terms.stream().mapToInt(term -> content.contains(term) ? term.length() : 0).sum();
    }

    private java.util.Map<Long, Double> semanticScores(long userId, String query,
                                                        List<AgentMemoryView> candidates) {
        AgentMemorySemanticRanker ranker = semanticRanker.getIfAvailable();
        if (ranker == null) return java.util.Map.of();
        try {
            return ranker.rank(userId, query, candidates).stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                            AgentMemorySemanticScore::memoryId, AgentMemorySemanticScore::score));
        } catch (RuntimeException unavailable) {
            // 语义召回是增强项。Embedding 超时、限流或模型离线时必须继续使用词法结果，
            // 不能因为可选向量服务故障让整个 Agent 对话失败。
            return java.util.Map.of();
        }
    }

    private static double combinedScore(AgentMemoryView memory, Set<String> terms,
                                        java.util.Map<Long, Double> semanticScores) {
        // 词法命中用于精确名称和原话，语义分用于同义改写；二者相加而不是互相覆盖。
        return score(memory, terms) + Math.max(0D, semanticScores.getOrDefault(memory.id(), 0D)) * 4D;
    }

    private static Set<String> terms(String query) {
        if (query == null) return Set.of();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String token : query.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").split("\\s+")) {
            if (token.length() >= 2) result.add(token);
            // 中文问句通常没有空格；若只把整句当一个 token，
            // “你记得我喜欢什么吗”便无法命中“我喜欢简洁回答”。因此有界地生成 2~4 字片段。
            if (token.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp)
                    == Character.UnicodeScript.HAN)) {
                int[] points = token.codePoints().toArray();
                for (int width : new int[]{4, 3, 2}) {
                    for (int index = 0; index + width <= points.length; index++) {
                        result.add(new String(points, index, width));
                    }
                }
            }
        }
        return result.stream().limit(48).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
