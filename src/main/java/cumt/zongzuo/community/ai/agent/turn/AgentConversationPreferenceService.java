package cumt.zongzuo.community.ai.agent.turn;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理当前用户唯一主对话的联网偏好。 */
@Service
public class AgentConversationPreferenceService {

    private final AgentTurnMapper mapper;

    public AgentConversationPreferenceService(AgentTurnMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public WebSearchPreference get(long userId) {
        mapper.ensureConversation(userId);
        return new WebSearchPreference(Boolean.TRUE.equals(
                mapper.selectConversationWebSearch(userId)));
    }

    /**
     * 临时对话只读取既有主对话偏好；如果用户尚无主对话，直接返回默认开启，绝不创建
     * conversation 行。这样开启临时模式不会留下任何持久会话事实。
     */
    @Transactional(readOnly = true)
    public WebSearchPreference getWithoutCreatingConversation(long userId) {
        Boolean enabled = mapper.selectConversationWebSearch(userId);
        return new WebSearchPreference(enabled == null || enabled);
    }

    @Transactional
    public WebSearchPreference update(long userId, boolean enabled) {
        mapper.ensureConversation(userId);
        if (mapper.updateConversationWebSearch(userId, enabled) != 1) {
            throw new IllegalStateException("Agent web search preference update failed");
        }
        return new WebSearchPreference(enabled);
    }

    public record WebSearchPreference(boolean enabled) { }
}
