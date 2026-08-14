package cumt.zongzuo.community.ai.agent.planner;

import java.util.List;

/** 一轮经过后端安全策略收敛后的只读工具计划。 */
public record AgentPlannerRound(List<AgentReadOnlyTool> tools, boolean reviewAfterExecution) {

    public AgentPlannerRound {
        tools = List.copyOf(tools);
    }
}
