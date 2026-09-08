package cumt.zongzuo.community.ai.agent.history;

import java.util.List;

/**
 * 一页成功问答的安全视图。游标由过滤前的数据库记录决定，不能用过滤后的消息数判断结束，
 * 否则遇到含凭据的一页会提前停止。messages 按时间正序，下一页使用排他的 turn ID 游标。
 */
public record AgentConversationPage(List<AgentConversationHistoryHit> messages,
                                     long nextBeforeTurnId, boolean exhausted) {
    public AgentConversationPage { messages = List.copyOf(messages); }

    public static AgentConversationPage empty() {
        return new AgentConversationPage(List.of(), Long.MAX_VALUE, true);
    }
}
