package cumt.zongzuo.community.ai.agent.turn;

import java.util.List;

/** 按 turnId 降序返回的主对话历史页；空游标表示已经到底。 */
public record AgentTurnHistoryPage(List<AgentTurnHistoryItem> items, Long nextBeforeTurnId) {

    public AgentTurnHistoryPage {
        items = List.copyOf(items);
    }
}
