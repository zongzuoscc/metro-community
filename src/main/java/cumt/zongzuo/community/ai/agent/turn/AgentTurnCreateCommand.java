package cumt.zongzuo.community.ai.agent.turn;

import java.util.Objects;
import java.util.UUID;

public record AgentTurnCreateCommand(long userId, UUID clientRequestId, String message,
                                     String pageContextJson, String taskType) {
    public AgentTurnCreateCommand {
        if (userId <= 0 || clientRequestId == null || message == null || message.isBlank()
                || message.length() > 4_000 || pageContextJson == null || taskType == null
                || taskType.isBlank() || taskType.length() > 32) {
            throw new IllegalArgumentException("Agent turn command is invalid");
        }
        message = message.strip();
        pageContextJson = Objects.requireNonNull(pageContextJson).strip();
        taskType = taskType.strip();
    }
}
