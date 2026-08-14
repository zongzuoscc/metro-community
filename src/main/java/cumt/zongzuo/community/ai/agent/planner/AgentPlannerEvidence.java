package cumt.zongzuo.community.ai.agent.planner;

/**
 * 上一轮只读工具返回的低敏摘要。
 *
 * <p>Planner 只需要知道各类证据是否存在，不需要再次接收文章正文、记忆内容或联网摘要。
 * 这样第二轮能够决定是否补查，同时避免把同一批用户数据重复发送给规划模型。</p>
 */
public record AgentPlannerEvidence(int communitySources, int memories, int historyMessages,
                                   int episodeSummaries, int webSources) {

    public AgentPlannerEvidence {
        if (communitySources < 0 || memories < 0 || historyMessages < 0
                || episodeSummaries < 0 || webSources < 0) {
            throw new IllegalArgumentException("Planner evidence counts must not be negative");
        }
    }

    public static AgentPlannerEvidence empty() {
        return new AgentPlannerEvidence(0, 0, 0, 0, 0);
    }

    /** 是否已经获得至少一条可交给回答模型的外部依据。 */
    public boolean hasAny() {
        return communitySources + memories + historyMessages + episodeSummaries + webSources > 0;
    }
}
