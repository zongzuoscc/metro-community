package cumt.zongzuo.community.ai.agent.history;

import org.springframework.stereotype.Service;
import cumt.zongzuo.community.ai.agent.memory.AgentMemorySafetyPolicy;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 按需检索当前用户的旧消息，与精简的长期画像记忆完全分离。
 * 该分离使“你记得我的偏好吗”和“我很久前说过什么”可以使用不同的权限、排序和审计证据。
 */
@Service
public class AgentConversationHistorySearchService {

    private static final Set<String> STOP = Set.of("我", "你", "的", "了", "吗", "呢", "说过",
            "什么", "这个", "the", "what", "did", "say");
    private static final Set<String> HARSH_RECALL = Set.of("最重", "难听", "伤人", "过分", "骂");
    private static final List<String> HARSH_TERMS = List.of(
            "差劲", "难用", "垃圾", "蠢", "笨", "没用", "不懂", "失望", "讨厌", "滚");
    private final AgentConversationHistoryMapper mapper;
    private final AgentMemorySafetyPolicy safety;

    public AgentConversationHistorySearchService(AgentConversationHistoryMapper mapper,
                                                 AgentMemorySafetyPolicy safety) {
        this.mapper = mapper;
        this.safety = safety;
    }

    /**
     * 对有界词法候选进行确定性排序。
     * 当问题明确询问“我说过什么”时，只保留 USER 角色消息，防止把助手自己的旧回答误当成用户原话。
     */
    public List<AgentConversationHistoryHit> search(long userId, String query, int limit) {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("Invalid history limit");
        List<String> terms = terms(query);
        if (terms.isEmpty()) return List.of();
        boolean userSelfRecall = query != null && (query.contains("我说过")
                || query.contains("我对你说") || query.contains("我曾说"));
        return mapper.searchCandidates(userId, terms, Long.MAX_VALUE, Math.min(200, limit * 20))
                .stream().filter(hit -> !userSelfRecall || "USER".equals(hit.role()))
                .filter(hit -> safety.canStore(hit.content())).sorted(Comparator.comparingInt(
                        (AgentConversationHistoryHit hit) -> score(hit.content(), terms)).reversed()
                        .thenComparing(AgentConversationHistoryHit::messageId,
                                Comparator.reverseOrder()))
                .limit(limit).toList();
    }

    /** 最近三个已完成摘要按时间正序返回，帮助模型理解跨 episode 的连续目标。 */
    public List<AgentEpisodeSummaryView> recentSummaries(long userId, int limit) {
        if (limit < 1 || limit > 8) throw new IllegalArgumentException("Invalid summary limit");
        List<AgentEpisodeSummaryView> newestFirst = mapper.recentSummaries(userId, limit);
        if (newestFirst == null || newestFirst.isEmpty()) return List.of();
        java.util.ArrayList<AgentEpisodeSummaryView> chronological =
                new java.util.ArrayList<>(newestFirst);
        java.util.Collections.reverse(chronological);
        return List.copyOf(chronological);
    }

    private static int score(String content, List<String> terms) {
        String normalized = content.toLowerCase(Locale.ROOT);
        return terms.stream().mapToInt(term -> normalized.contains(term) ? term.length() : 0).sum();
    }

    private static List<String> terms(String query) {
        if (query == null) return List.of();
        String normalized = query.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2 && !STOP.contains(token)) result.add(token);
            if (token.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp)
                    == Character.UnicodeScript.HAN)) {
                int[] points = token.codePoints().toArray();
                for (int width : new int[]{4, 3, 2}) {
                    for (int index = 0; index + width <= points.length; index++) {
                        String gram = new String(points, index, width);
                        if (!STOP.contains(gram)) result.add(gram);
                    }
                }
            }
        }
        if (HARSH_RECALL.stream().anyMatch(normalized::contains)) {
            result.addAll(HARSH_TERMS);
        }
        return result.stream().limit(24).toList();
    }
}
