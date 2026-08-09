package cumt.zongzuo.community.ai.config;

import cumt.zongzuo.community.ai.provider.AiCapability;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MetroAiPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsEveryCapabilityOffAndUsesPlannedProviderModels() {
        contextRunner.run(context -> {
            MetroAiProperties properties = context.getBean(MetroAiProperties.class);

            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getAgent().isEnabled()).isFalse();
            assertThat(properties.getMemory().isEnabled()).isFalse();
            assertThat(properties.getWriting().isEnabled()).isFalse();
            assertThat(properties.getModeration().isEnabled()).isFalse();
            assertThat(properties.getEmbedding().isEnabled()).isFalse();
            assertThat(properties.getDeepSeek().getModel()).isEqualTo("deepseek-v4-flash");
            assertThat(properties.getOllama().getModel()).isEqualTo("bge-m3");
            assertThat(properties.getAgent().getMaxInputCharacters()).isEqualTo(4_000);
            assertThat(properties.getAgent().getPerMinute()).isEqualTo(8);
            assertThat(properties.getAgent().getPerDay()).isEqualTo(100);
            assertThat(properties.getAgent().getQuotaWindow()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.getAgent().getTimeout()).isEqualTo(Duration.ofSeconds(45));
            assertThat(properties.getAgent().getBulkhead()).isEqualTo(8);
            assertThat(properties.getArticleSummary().getMaxInputCharacters()).isEqualTo(100_000);
            assertThat(properties.getArticleSummary().getPerMinute()).isEqualTo(5);
            assertThat(properties.getArticleSummary().getPerDay()).isEqualTo(30);
            assertThat(properties.getArticleSummary().getQuotaWindow()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.getArticleSummary().getTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.getWriting().getMaxInputCharacters()).isEqualTo(20_000);
            assertThat(properties.getWriting().getPerMinute()).isEqualTo(10);
            assertThat(properties.getWriting().getPerDay()).isEqualTo(60);
            assertThat(properties.getWriting().getQuotaWindow()).isEqualTo(Duration.ofMinutes(10));
            assertThat(properties.getWriting().getTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.getArticleSummary().getBulkhead()).isEqualTo(4);
            assertThat(properties.getWriting().getBulkhead()).isEqualTo(4);
            assertThat(properties.getHyde().getBulkhead()).isEqualTo(8);
            assertThat(properties.getHyde().getMaxOutputCharacters()).isEqualTo(600);
            assertThat(properties.getHyde().getTimeout()).isEqualTo(Duration.ofSeconds(8));
            assertThat(properties.getModeration().getTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(properties.getModeration().getTaskTimeout()).isEqualTo(Duration.ofSeconds(90));
            assertThat(properties.getModeration().getBulkhead()).isEqualTo(2);
            assertThat(properties.getMemory().getTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(properties.getMemory().getBulkhead()).isEqualTo(2);
            assertThat(properties.getEmbedding().getTimeout()).isEqualTo(Duration.ofSeconds(45));
            assertThat(properties.getEmbedding().getBulkhead()).isEqualTo(4);
            assertThat(properties.getRuntime().getQuotaNamespace()).isEqualTo("metro:ai:quota");
            assertThat(properties.getRuntime().getRetryDelay()).isEqualTo(Duration.ofMillis(100));
            assertThat(properties.getRuntime().getInteractiveMaxAttempts()).isEqualTo(2);
            assertThat(properties.getRuntime().getBackgroundMaxAttempts()).isEqualTo(3);
            assertThat(properties.getRuntime().getCircuitSlidingWindowSize()).isEqualTo(20);
            assertThat(properties.getRuntime().getCircuitMinimumCalls()).isEqualTo(10);
            assertThat(properties.getRuntime().getCircuitFailureRateThreshold()).isEqualTo(50.0f);
            assertThat(properties.getRuntime().getCircuitOpenStateWaitDuration())
                    .isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getRuntime().getCircuitPermittedCallsInHalfOpen()).isEqualTo(2);
            assertThat(properties.getRuntime().getShutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getRuntime().getProviderConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getRuntime().getProviderTimeoutMargin()).isEqualTo(Duration.ofSeconds(1));
        });
    }

    @Test
    void bindsFlagsProvidersAndPerCapabilityLimits() {
        contextRunner.withPropertyValues(
                        "metro.ai.enabled=true",
                        "metro.ai.agent.enabled=true",
                        "metro.ai.memory.enabled=true",
                        "metro.ai.writing.enabled=true",
                        "metro.ai.moderation.enabled=true",
                        "metro.ai.embedding.enabled=true",
                        "metro.ai.deep-seek.base-url=http://127.0.0.1:18080",
                        "metro.ai.deep-seek.api-key=test-key",
                        "metro.ai.deep-seek.model=chat-test",
                        "metro.ai.ollama.base-url=http://127.0.0.1:11435",
                        "metro.ai.ollama.model=embed-test",
                        "metro.ai.agent.max-input-characters=1234",
                        "metro.ai.agent.per-minute=3",
                        "metro.ai.agent.per-day=9",
                        "metro.ai.agent.timeout=PT7S",
                        "metro.ai.agent.bulkhead=2",
                        "metro.ai.article-summary.max-input-characters=9000",
                        "metro.ai.writing.timeout=PT11S",
                        "metro.ai.writing.quota-window=PT12M",
                        "metro.ai.hyde.max-output-characters=321",
                        "metro.ai.moderation.task-timeout=PT33S",
                        "metro.ai.embedding.bulkhead=6")
                .run(context -> {
                    MetroAiProperties properties = context.getBean(MetroAiProperties.class);

                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getAgent().isEnabled()).isTrue();
                    assertThat(properties.getMemory().isEnabled()).isTrue();
                    assertThat(properties.getWriting().isEnabled()).isTrue();
                    assertThat(properties.getModeration().isEnabled()).isTrue();
                    assertThat(properties.getEmbedding().isEnabled()).isTrue();
                    assertThat(properties.getDeepSeek().getBaseUrl()).isEqualTo("http://127.0.0.1:18080");
                    assertThat(properties.getDeepSeek().getApiKey()).isEqualTo("test-key");
                    assertThat(properties.getDeepSeek().getModel()).isEqualTo("chat-test");
                    assertThat(properties.getOllama().getBaseUrl()).isEqualTo("http://127.0.0.1:11435");
                    assertThat(properties.getOllama().getModel()).isEqualTo("embed-test");
                    assertThat(properties.getAgent().getMaxInputCharacters()).isEqualTo(1234);
                    assertThat(properties.getAgent().getPerMinute()).isEqualTo(3);
                    assertThat(properties.getAgent().getPerDay()).isEqualTo(9);
                    assertThat(properties.getAgent().getTimeout()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(properties.getAgent().getBulkhead()).isEqualTo(2);
                    assertThat(properties.getArticleSummary().getMaxInputCharacters()).isEqualTo(9000);
                    assertThat(properties.getWriting().getTimeout()).isEqualTo(Duration.ofSeconds(11));
                    assertThat(properties.getWriting().getQuotaWindow()).isEqualTo(Duration.ofMinutes(12));
                    assertThat(properties.getHyde().getMaxOutputCharacters()).isEqualTo(321);
                    assertThat(properties.getModeration().getTaskTimeout()).isEqualTo(Duration.ofSeconds(33));
                    assertThat(properties.getEmbedding().getBulkhead()).isEqualTo(6);
                });
    }

    @Test
    void articleSummaryAndHydeInheritTheAgentBusinessFlagThroughOneCapabilityMapping() {
        contextRunner.withPropertyValues("metro.ai.enabled=true", "metro.ai.agent.enabled=true")
                .run(context -> {
                    MetroAiProperties properties = context.getBean(MetroAiProperties.class);

                    assertThat(properties.isCapabilityEnabled(AiCapability.AGENT)).isTrue();
                    assertThat(properties.isCapabilityEnabled(AiCapability.ARTICLE_SUMMARY)).isTrue();
                    assertThat(properties.isCapabilityEnabled(AiCapability.HYDE)).isTrue();
                    assertThat(properties.isCapabilityEnabled(AiCapability.WRITING)).isFalse();
                    assertThat(properties.isCapabilityEnabled(AiCapability.MODERATION)).isFalse();
                    assertThat(properties.isCapabilityEnabled(AiCapability.MEMORY_EXTRACTION)).isFalse();
                    assertThat(properties.isCapabilityEnabled(AiCapability.EMBEDDING)).isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MetroAiProperties.class)
    static class PropertiesConfiguration {
    }
}
