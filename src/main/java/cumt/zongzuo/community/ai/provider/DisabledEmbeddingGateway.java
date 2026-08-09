package cumt.zongzuo.community.ai.provider;

import java.util.Objects;

public final class DisabledEmbeddingGateway implements EmbeddingGateway {

    private final AiProviderErrorReason reason;

    public DisabledEmbeddingGateway(AiProviderErrorReason reason) {
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    @Override
    public EmbeddingResult embed(EmbeddingCommand command) {
        throw new AiProviderException(reason, message(reason));
    }

    private static String message(AiProviderErrorReason reason) {
        return reason == AiProviderErrorReason.AI_DISABLED
                ? "AI capability is disabled"
                : "AI provider is unavailable";
    }
}
