package cumt.zongzuo.community.ai.agent.history;

/** 送入滚动摘要器的已提交消息快照，仅包含角色和正文。 */
public record AgentEpisodeMessage(String role, String content) {
}
