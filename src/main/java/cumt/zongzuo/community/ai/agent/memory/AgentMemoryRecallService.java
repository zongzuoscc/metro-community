package cumt.zongzuo.community.ai.agent.memory;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AgentMemoryRecallService {

    private final AgentMemoryMapper mapper;

    public AgentMemoryRecallService(AgentMemoryMapper mapper) {
        this.mapper = mapper;
    }

    public List<AgentMemoryView> recall(long userId, String query, int limit) {
        if (limit < 1 || limit > 16) throw new IllegalArgumentException("Invalid memory limit");
        mapper.ensureSetting(userId);
        if (!Boolean.TRUE.equals(mapper.enabled(userId))) return List.of();
        Set<String> terms = terms(query);
        return mapper.listActive(userId, 100).stream()
                .sorted(Comparator.comparingInt((AgentMemoryView memory) -> score(memory, terms))
                        .reversed().thenComparing(AgentMemoryView::id, Comparator.reverseOrder()))
                .filter(memory -> terms.isEmpty() || score(memory, terms) > 0
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
        return terms.stream().mapToInt(term -> content.contains(term) ? 1 : 0).sum();
    }

    private static Set<String> terms(String query) {
        if (query == null) return Set.of();
        return java.util.Arrays.stream(query.toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L}\\p{N}]+", " ").split("\\s+"))
                .filter(term -> term.length() >= 2).collect(Collectors.toSet());
    }
}
