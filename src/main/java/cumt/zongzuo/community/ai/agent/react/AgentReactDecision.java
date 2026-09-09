package cumt.zongzuo.community.ai.agent.react;

/**
 * ReAct 的一步决策。{@code call == null} 严格表示检索已完成；
 * 非空时表示调用且只调用该只读工具。
 */
public record AgentReactDecision(AgentToolCall call) {
}
