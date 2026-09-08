package cumt.zongzuo.community.ai.provider;

import java.util.List;
import java.util.Objects;

public record AiChatCommand(AiCapability capability, List<AiPromptMessage> messages,
                            AiResponseMode responseMode, Integer maxOutputTokens) {

    /** 旧能力继续使用各自默认输出策略，Agent 可显式传入预算中的输出上限。 */
    public AiChatCommand(AiCapability capability, List<AiPromptMessage> messages,
                         AiResponseMode responseMode) {
        this(capability, messages, responseMode, null);
    }

    public AiChatCommand {
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(responseMode, "responseMode must not be null");
        if (maxOutputTokens != null && maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        messages = List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
    }
}
