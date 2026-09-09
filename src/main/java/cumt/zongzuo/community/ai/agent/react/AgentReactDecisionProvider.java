package cumt.zongzuo.community.ai.agent.react;

import cumt.zongzuo.community.ai.provider.AiPromptMessage;
import cumt.zongzuo.community.ai.userprovider.PreparedUserAiChat;

import java.time.Instant;
import java.util.List;

/** 为单轮问答提供有界、只读的 ReAct 决策。 */
public interface AgentReactDecisionProvider {

    AgentReactDecision decide(long userId, String requestId, String question,
                              List<AiPromptMessage> recentContext,
                              boolean persistentAllowed, boolean webEnabled,
                              int step, int remaining,
                              List<AgentToolObservation> observations,
                              PreparedUserAiChat route, Instant deadline);

    /**
     * 只用当前原始问题与公开证据生成可发往联网服务的查询。
     *
     * <p>默认失败关闭，避免替代实现继续把含私有上下文的普通
     * ReAct 决策直接当作外部搜索词。</p>
     */
    default String publicWebQuery(long userId, String requestId, String question,
                                  List<AgentToolObservation> publicObservations,
                                  PreparedUserAiChat route, Instant deadline) {
        throw new IllegalStateException("Agent decision provider has no public web query boundary");
    }

    int maxRounds();

    int maxToolCalls();
}
