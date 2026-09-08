package cumt.zongzuo.community.ai.agent.context;

import cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 轻量追问消歧：为短指代问题附带最近明确的用户话题，仅用于站内检索和规划。
 * 不额外调用模型，不宣称完成了语义改写；最终回答仍读取原始问题及完整近期问答。
 * 尤其不能把拼入的私有历史直接传给公网搜索服务。
 */
public final class AgentFollowUpQuery {
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "继续|接着|展开|详细|具体|例子|这个|那个|上面|刚才|第[一二三四五六七八九十0-9]+个|"
                    + "(?i:continue|elaborate|another example|the third)");

    private AgentFollowUpQuery() { }

    public static String forInternalRetrieval(String question, List<AgentConversationHistoryHit> recent) {
        if (!isFollowUp(question)) return question;
        for (int index = recent.size() - 1; index >= 0; index--) {
            var message = recent.get(index);
            if (!"USER".equals(message.role()) || isFollowUp(message.content())) continue;
            String topic = message.content();
            int end = topic.offsetByCodePoints(0, Math.min(384, topic.codePointCount(0, topic.length())));
            return "此前讨论的问题：" + topic.substring(0, end) + "\n当前追问：" + question;
        }
        return question;
    }

    private static boolean isFollowUp(String question) {
        return question.length() <= 80 && FOLLOW_UP.matcher(question).find();
    }
}
