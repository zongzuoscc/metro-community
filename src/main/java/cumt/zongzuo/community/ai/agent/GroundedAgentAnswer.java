package cumt.zongzuo.community.ai.agent;

import java.util.List;

public record GroundedAgentAnswer(String answer, List<AgentCitation> citations,
                                  String finishReason) {

    public GroundedAgentAnswer {
        citations = List.copyOf(citations);
    }
}
