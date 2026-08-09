package cumt.zongzuo.community.ai.runtime;

import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.provider.AiCapability;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class AiCapabilityPolicyResolver {

    private final Map<AiCapability, AiCapabilityPolicy> policies;
    private final MetroAiProperties.RuntimeProperties runtime;

    public AiCapabilityPolicyResolver(MetroAiProperties properties) {
        Objects.requireNonNull(properties, "properties");
        this.runtime = Objects.requireNonNull(properties.getRuntime(), "metro.ai.runtime");
        EnumMap<AiCapability, AiCapabilityPolicy> resolved = new EnumMap<>(AiCapability.class);
        for (AiCapability capability : AiCapability.values()) {
            MetroAiProperties.CapabilityProperties capabilityProperties = properties(capability, properties);
            MetroAiProperties.CapabilityProperties quotaProperties = capability == AiCapability.HYDE
                    ? properties.getAgent() : capabilityProperties;
            boolean embedding = capability == AiCapability.EMBEDDING;
            resolved.put(capability, new AiCapabilityPolicy(capability,
                    capability == AiCapability.HYDE ? AiCapability.AGENT : capability,
                    properties.isCapabilityEnabled(capability),
                    capabilityProperties.getMaxInputCharacters(),
                    quotaProperties.getPerMinute(), quotaProperties.getPerDay(),
                    quotaProperties.getQuotaWindow(), capabilityProperties.getTimeout(),
                    capabilityProperties.getBulkhead(), embedding ? "ollama" : "deepseek",
                    embedding ? properties.getOllama().getModel() : properties.getDeepSeek().getModel()));
        }
        this.policies = Collections.unmodifiableMap(resolved);
    }

    public AiCapabilityPolicyResolver(Map<AiCapability, AiCapabilityPolicy> policies,
                                      MetroAiProperties.RuntimeProperties runtime) {
        Objects.requireNonNull(policies, "policies");
        this.policies = Collections.unmodifiableMap(new EnumMap<>(policies));
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public AiCapabilityPolicy resolve(AiCapability capability) {
        AiCapabilityPolicy policy = policies.get(Objects.requireNonNull(capability, "capability"));
        if (policy == null) {
            throw new AiExecutionException(AiExecutionErrorReason.AGENT_RUNTIME_UNAVAILABLE,
                    "No runtime policy is configured for AI capability " + capability);
        }
        return policy;
    }

    public Map<AiCapability, AiCapabilityPolicy> policies() {
        return policies;
    }

    public MetroAiProperties.RuntimeProperties runtime() {
        return runtime;
    }

    private static MetroAiProperties.CapabilityProperties properties(
            AiCapability capability, MetroAiProperties properties) {
        return switch (capability) {
            case AGENT -> properties.getAgent();
            case ARTICLE_SUMMARY -> properties.getArticleSummary();
            case WRITING -> properties.getWriting();
            case HYDE -> properties.getHyde();
            case MODERATION -> properties.getModeration();
            case MEMORY_EXTRACTION -> properties.getMemory();
            case EMBEDDING -> properties.getEmbedding();
        };
    }
}
