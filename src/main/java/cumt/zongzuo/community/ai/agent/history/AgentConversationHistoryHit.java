package cumt.zongzuo.community.ai.agent.history;

import java.time.LocalDateTime;

public record AgentConversationHistoryHit(long messageId, long turnId, long userId,
                                          String role, String content,
                                          LocalDateTime createdAt) {
}
