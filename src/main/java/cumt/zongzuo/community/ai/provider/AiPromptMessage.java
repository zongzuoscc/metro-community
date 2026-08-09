package cumt.zongzuo.community.ai.provider;

import java.util.Objects;

public record AiPromptMessage(AiPromptRole role, String text) {

    public AiPromptMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(text, "text must not be null");
    }
}
