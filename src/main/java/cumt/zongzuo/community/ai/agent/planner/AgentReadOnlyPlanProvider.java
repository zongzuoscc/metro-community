package cumt.zongzuo.community.ai.agent.planner;

import java.time.Instant;
import java.util.Set;

/**
 * 回答链只依赖这一窄接口获取计划，工具实现不会暴露给规划模型。
 */
public interface AgentReadOnlyPlanProvider {

    AgentPlannerRound plan(long userId, String requestId, String question,
                           boolean persistentContextAllowed, boolean webSearchEnabled,
                           int round, Set<AgentReadOnlyTool> alreadyCalled,
                           AgentPlannerEvidence evidence, Instant deadline);

    int maxRounds();

    int maxToolCalls();
}
