package cumt.zongzuo.community.ai.runtime;

import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.ai.provider.AiProviderErrorReason;
import cumt.zongzuo.community.ai.provider.AiProviderException;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AiMetricsTest {

    private static final Set<String> ALLOWED_TAG_KEYS = Set.of("capability", "provider", "model", "outcome");

    @Test
    void recordsOneRequestPerInvocationOneProviderFailurePerAttemptAndSuccessTokensOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry);
        Map<AiCapability, AiCapabilityPolicy> policies = policies();
        MetroAiProperties.RuntimeProperties runtime = runtime();
        AtomicInteger quotaCalls = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        try (DefaultAiCapabilityExecutor executor = new DefaultAiCapabilityExecutor(
                new AiCapabilityPolicyResolver(policies, runtime),
                context -> quotaCalls.incrementAndGet(), metrics, Clock.systemUTC())) {
            AiChatResult result = executor.execute(new AiInvocationContext(AiCapability.AGENT, 7L,
                    "high-cardinality-request-id", 10, Instant.now().plusSeconds(2), false), () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new AiProviderException(AiProviderErrorReason.RATE_LIMITED, 429,
                            "rate limited", null);
                }
                return new AiChatResult("ok", "stop", 12, 4, "untrusted-provider", "untrusted-model");
            });

            assertThat(result.text()).isEqualTo("ok");
        }

        assertThat(quotaCalls).hasValue(1);
        assertThat(attempts).hasValue(2);
        assertThat(registry.get("ai.request.count").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ai.request.latency").timer().count()).isEqualTo(1);
        assertThat(registry.get("ai.request.tokens").summary().totalAmount()).isEqualTo(16.0);
        assertThat(registry.get("provider.429").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("ai.circuit.state").gauges()).hasSize(policies.size());
        assertThat(registry.getMeters()).allSatisfy(meter -> assertLowCardinality(meter.getId()));
        assertThat(registry.getMeters()).allSatisfy(meter -> {
            assertThat(meter.getId().getTag("capability")).doesNotContain("7");
            assertThat(meter.getId().getTag("provider")).isNotEqualTo("untrusted-provider");
            assertThat(meter.getId().getTag("model")).isNotEqualTo("untrusted-model");
        });
        registry.close();
    }

    @Test
    void countsAdmissionRejectionsWithoutRecordingTokens() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry);
        AiCapabilityPolicy policy = policies().get(AiCapability.AGENT);

        metrics.recordQuotaRejected(policy, "short_window");
        metrics.recordBulkheadRejected(policy);

        assertThat(registry.get("ai.quota.rejected").tag("outcome", "short_window").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("ai.bulkhead.rejected").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("ai.request.tokens").meters()).isEmpty();
        assertThat(registry.getMeters()).allSatisfy(meter -> assertLowCardinality(meter.getId()));
        registry.close();
    }

    @Test
    void exposesOnlyTheApprovedProviderFailureMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry);
        AiCapabilityPolicy policy = policies().get(AiCapability.AGENT);

        metrics.recordProviderFailure(policy,
                new AiProviderException(AiProviderErrorReason.TIMEOUT, "timeout"));
        metrics.recordProviderFailure(policy,
                new AiProviderException(AiProviderErrorReason.RATE_LIMITED, 429, "rate", null));
        metrics.recordProviderFailure(policy,
                new AiProviderException(AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE, 503, "failure", null));
        metrics.recordProviderFailure(policy,
                new AiProviderException(AiProviderErrorReason.CONNECTION_FAILURE, "connection"));

        assertThat(registry.get("provider.timeout").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("provider.429").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("provider.5xx").counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertLowCardinality(meter.getId()));
        registry.close();
    }

    private static void assertLowCardinality(Meter.Id id) {
        assertThat(id.getTags()).allSatisfy(tag -> assertThat(ALLOWED_TAG_KEYS).contains(tag.getKey()));
        assertThat(id.getTags()).noneSatisfy(tag -> assertThat(tag.getKey())
                .isIn("userId", "requestId", "articleId", "prompt", "url"));
    }

    private static Map<AiCapability, AiCapabilityPolicy> policies() {
        Map<AiCapability, AiCapabilityPolicy> policies = new EnumMap<>(AiCapability.class);
        policies.put(AiCapability.AGENT, new AiCapabilityPolicy(AiCapability.AGENT, AiCapability.AGENT,
                true, 4_000, 100, 100, Duration.ofMinutes(1), Duration.ofSeconds(1), 2,
                "deepseek", "deepseek-v4-flash"));
        policies.put(AiCapability.EMBEDDING, new AiCapabilityPolicy(AiCapability.EMBEDDING,
                AiCapability.EMBEDDING, true, 100_000, 0, 0, Duration.ofMinutes(1),
                Duration.ofSeconds(1), 1, "ollama", "bge-m3"));
        return policies;
    }

    private static MetroAiProperties.RuntimeProperties runtime() {
        MetroAiProperties.RuntimeProperties runtime = new MetroAiProperties.RuntimeProperties();
        runtime.setRetryDelay(Duration.ZERO);
        runtime.setCircuitMinimumCalls(10);
        runtime.setCircuitSlidingWindowSize(10);
        return runtime;
    }
}
