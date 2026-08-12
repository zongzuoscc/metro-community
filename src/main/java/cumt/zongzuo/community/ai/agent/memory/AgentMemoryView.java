package cumt.zongzuo.community.ai.agent.memory;

/** 向当前所有者展示的记忆快照，只包含当前版本与可对外状态，不暴露内部来源或投影细节。 */
public record AgentMemoryView(long id, String category, String content, long version,
                              String state) {
}
