package cumt.zongzuo.community.ai.provider;

import java.util.List;
import java.util.Objects;

public record AiChatCommand(AiCapability capability, List<AiPromptMessage> messages,
                            AiResponseMode responseMode) {

    public AiChatCommand {
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(responseMode, "responseMode must not be null");
        messages = List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
    }
}
