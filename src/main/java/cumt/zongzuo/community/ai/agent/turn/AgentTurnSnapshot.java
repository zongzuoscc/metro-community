package cumt.zongzuo.community.ai.agent.turn;

import java.time.LocalDateTime;

public record AgentTurnSnapshot(long turnId, String state, String taskType, boolean temporary,
                                LocalDateTime createdAt, LocalDateTime startedAt,
                                LocalDateTime completedAt, String userMessage,
                                String partialMessage, String finalMessage, Long messageId,
                                int citationCount, String error) {
}
