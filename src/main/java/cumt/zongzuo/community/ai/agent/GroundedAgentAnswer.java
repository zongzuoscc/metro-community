package cumt.zongzuo.community.ai.agent;

import java.util.List;

public record GroundedAgentAnswer(String answer, List<AgentCitation> citations,
                                  String finishReason, List<AgentMemoryUse> memoryUses,
                                  List<AgentHistoryUse> historyUses) {

    public GroundedAgentAnswer {
        citations = List.copyOf(citations);
        memoryUses = List.copyOf(memoryUses);
        historyUses = List.copyOf(historyUses);
    }

    public GroundedAgentAnswer(String answer, List<AgentCitation> citations, String finishReason) {
        this(answer, citations, finishReason, List.of(), List.of());
    }
}
