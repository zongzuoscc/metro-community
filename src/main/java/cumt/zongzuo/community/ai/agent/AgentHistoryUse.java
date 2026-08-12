package cumt.zongzuo.community.ai.agent;

import java.time.LocalDateTime;

public record AgentHistoryUse(long messageId, long turnId, String role, String content,
                              LocalDateTime createdAt) {
}
