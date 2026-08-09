package cumt.zongzuo.community.ai.runtime;

import cumt.zongzuo.community.ai.provider.AiCapability;

import java.time.Instant;
import java.util.Objects;

public record AiInvocationContext(AiCapability capability, Long userId, String requestId,
                                  int inputCharacters, Instant deadline, boolean background) {

    public AiInvocationContext {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(deadline, "deadline");
        if (inputCharacters < 0) {
            throw new IllegalArgumentException("inputCharacters must not be negative");
        }
    }
}
