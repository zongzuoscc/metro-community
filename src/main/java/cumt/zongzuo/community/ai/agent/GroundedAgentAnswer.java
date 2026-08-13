package cumt.zongzuo.community.ai.agent;

import java.util.List;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSource;
import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;

public record GroundedAgentAnswer(String answer, List<AgentCitation> citations,
                                  String finishReason, List<AgentMemoryUse> memoryUses,
                                  List<AgentHistoryUse> historyUses,
                                  List<AgentWebSource> webSources,
                                  UserAiFundingSource fundingSource,
                                  String provider, String model) {

    public GroundedAgentAnswer {
        citations = List.copyOf(citations);
        memoryUses = List.copyOf(memoryUses);
        historyUses = List.copyOf(historyUses);
        webSources = List.copyOf(webSources);
    }

    public GroundedAgentAnswer(String answer, List<AgentCitation> citations, String finishReason) {
        this(answer, citations, finishReason, List.of(), List.of(), List.of(),
                UserAiFundingSource.PLATFORM, null, null);
    }

    /** 保留现有持久化测试与调用方的五参数构造，默认归属平台额度。 */
    public GroundedAgentAnswer(String answer, List<AgentCitation> citations, String finishReason,
                               List<AgentMemoryUse> memoryUses, List<AgentHistoryUse> historyUses) {
        this(answer, citations, finishReason, memoryUses, historyUses, List.of(),
                UserAiFundingSource.PLATFORM, null, null);
    }
}
