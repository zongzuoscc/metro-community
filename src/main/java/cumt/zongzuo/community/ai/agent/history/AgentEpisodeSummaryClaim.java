package cumt.zongzuo.community.ai.agent.history;

/** Worker 领取待摘要 episode 时使用的所有者与旧状态快照。 */
public record AgentEpisodeSummaryClaim(long episodeId, long userId, String state) {
}
