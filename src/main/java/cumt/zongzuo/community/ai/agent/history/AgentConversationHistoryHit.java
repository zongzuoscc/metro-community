package cumt.zongzuo.community.ai.agent.history;

import java.time.LocalDateTime;

/**
 * 当前用户的历史消息命中。
 * 它是问题触发的临时检索证据，不是被提炼后的长期画像记忆，因此两者需要分开记录与展示。
 */
public record AgentConversationHistoryHit(long messageId, long turnId, long userId,
                                          String role, String content,
                                          LocalDateTime createdAt) {
}
