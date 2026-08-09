package cumt.zongzuo.community.ai.provider;

import java.util.Objects;

public final class DisabledAiChatGateway implements AiChatGateway {

    private final AiProviderErrorReason reason;

    public DisabledAiChatGateway(AiProviderErrorReason reason) {
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    @Override
    public AiChatResult generate(AiChatCommand command) {
        throw new AiProviderException(reason, message(reason));
    }

    private static String message(AiProviderErrorReason reason) {
        return reason == AiProviderErrorReason.AI_DISABLED
                ? "AI capability is disabled"
                : "AI provider is unavailable";
    }
}
