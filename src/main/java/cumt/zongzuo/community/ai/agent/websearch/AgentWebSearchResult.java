package cumt.zongzuo.community.ai.agent.websearch;

import java.util.List;

/** 百炼联网检索返回的受限摘要和可追溯来源。 */
public record AgentWebSearchResult(String summary, List<AgentWebSource> sources) {

    public AgentWebSearchResult {
        summary = summary == null ? "" : summary.strip();
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public static AgentWebSearchResult empty() {
        return new AgentWebSearchResult("", List.of());
    }
}
