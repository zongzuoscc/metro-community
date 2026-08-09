package cumt.zongzuo.community.ai.runtime;

import cumt.zongzuo.community.ai.provider.AiCapability;

import java.time.Duration;
import java.util.Objects;

public record AiCapabilityPolicy(AiCapability capability, AiCapability quotaGroup, boolean enabled,
                                 int maxInputCharacters, int shortWindowLimit, int dailyLimit,
                                 Duration quotaWindow, Duration timeout, int maxConcurrency,
                                 String provider, String model) {

    public AiCapabilityPolicy {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(quotaGroup, "quotaGroup");
        Objects.requireNonNull(quotaWindow, "quotaWindow");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        if (maxInputCharacters < 0 || shortWindowLimit < 0 || dailyLimit < 0
                || quotaWindow.isZero() || quotaWindow.isNegative()
                || timeout.isZero() || timeout.isNegative() || maxConcurrency <= 0) {
            throw new IllegalArgumentException("AI capability limits must be positive or explicitly disabled");
        }
    }
}
