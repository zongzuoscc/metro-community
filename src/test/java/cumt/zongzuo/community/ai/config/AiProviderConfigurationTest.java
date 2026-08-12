package cumt.zongzuo.community.ai.config;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatCommand;
import cumt.zongzuo.community.ai.provider.AiChatGateway;
import cumt.zongzuo.community.ai.provider.AiPromptMessage;
import cumt.zongzuo.community.ai.provider.AiPromptRole;
import cumt.zongzuo.community.ai.provider.AiProviderErrorReason;
import cumt.zongzuo.community.ai.provider.AiProviderException;
import cumt.zongzuo.community.ai.provider.AiResponseMode;
import cumt.zongzuo.community.ai.provider.DisabledAiChatGateway;
import cumt.zongzuo.community.ai.provider.DisabledEmbeddingGateway;
import cumt.zongzuo.community.ai.provider.EmbeddingCommand;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import cumt.zongzuo.community.ai.provider.OpenAiCompatibleAiChatGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiProviderConfiguration.class);

    @Test
    void allOffCreatesOnlyDisabledGatewaysAndNoProviderObjects() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AiChatGateway.class);
            assertThat(context).hasSingleBean(EmbeddingGateway.class);
            assertThat(context.getBean(AiChatGateway.class)).isInstanceOf(DisabledAiChatGateway.class);
            assertThat(context.getBean(EmbeddingGateway.class)).isInstanceOf(DisabledEmbeddingGateway.class);
            assertThat(context).doesNotHaveBean(OllamaApi.class);
            assertThat(context).doesNotHaveBean(OllamaEmbeddingModel.class);
        });
    }

    @Test
    void disabledGatewaysReportBusinessDisablementWithoutNetworkAccess() {
        contextRunner.run(context -> {
            AiChatGateway chatGateway = context.getBean(AiChatGateway.class);
            EmbeddingGateway embeddingGateway = context.getBean(EmbeddingGateway.class);

            assertThatThrownBy(() -> chatGateway.generate(chatCommand()))
                    .isInstanceOfSatisfying(AiProviderException.class,
                            error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.AI_DISABLED));
            assertThatThrownBy(() -> embeddingGateway.embed(
                    new EmbeddingCommand(AiCapability.EMBEDDING, List.of("hello"))))
                    .isInstanceOfSatisfying(AiProviderException.class,
                            error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.AI_DISABLED));
        });
    }

    @Test
    void enabledCapabilitiesWithoutCredentialsOrEndpointReportUnavailable() {
        contextRunner.withPropertyValues(
                        "metro.ai.enabled=true",
                        "metro.ai.agent.enabled=true",
                        "metro.ai.embedding.enabled=true",
                        "metro.ai.platform.api-key=",
                        "metro.ai.ollama.base-url=")
                .run(context -> {
                    assertThatThrownBy(() -> context.getBean(AiChatGateway.class).generate(chatCommand()))
                            .isInstanceOfSatisfying(AiProviderException.class,
                                    error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.AI_UNAVAILABLE));
                    assertThatThrownBy(() -> context.getBean(EmbeddingGateway.class).embed(
                            new EmbeddingCommand(AiCapability.EMBEDDING, List.of("hello"))))
                            .isInstanceOfSatisfying(AiProviderException.class,
                                    error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.AI_UNAVAILABLE));
                    assertThat(context).doesNotHaveBean(OllamaApi.class);
                });
    }

    @Test
    void configuredPlatformUsesTheGenericOpenAiCompatibleGateway() {
        contextRunner.withPropertyValues(
                        "metro.ai.enabled=true",
                        "metro.ai.agent.enabled=true",
                        "metro.ai.platform.provider=qwen",
                        "metro.ai.platform.base-url=https://example.invalid/compatible-mode/v1",
                        "metro.ai.platform.api-key=test-key",
                        "metro.ai.platform.model=qwen-plus")
                .run(context -> assertThat(context.getBean(AiChatGateway.class))
                        .isInstanceOf(OpenAiCompatibleAiChatGateway.class));
    }

    @Test
    void configuredProviderStillRejectsAChatCapabilityWhoseBusinessFlagIsOffWithoutNetworkIo() {
        contextRunner.withPropertyValues(
                        "metro.ai.enabled=true",
                        "metro.ai.agent.enabled=true",
                        "metro.ai.moderation.enabled=false",
                        "metro.ai.platform.base-url=http://127.0.0.1:1",
                        "metro.ai.platform.api-key=test-key")
                .run(context -> assertThatThrownBy(() -> context.getBean(AiChatGateway.class).generate(
                        new AiChatCommand(AiCapability.MODERATION,
                                List.of(new AiPromptMessage(AiPromptRole.USER, "moderate")), AiResponseMode.TEXT)))
                        .isInstanceOfSatisfying(AiProviderException.class,
                                error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.AI_DISABLED)));
    }

    @Test
    void embeddingGatewayRejectsNonEmbeddingCapabilityWithoutNetworkIo() {
        contextRunner.withPropertyValues(
                        "metro.ai.enabled=true",
                        "metro.ai.embedding.enabled=true",
                        "metro.ai.ollama.base-url=http://127.0.0.1:1")
                .run(context -> assertThatThrownBy(() -> context.getBean(EmbeddingGateway.class).embed(
                        new EmbeddingCommand(AiCapability.AGENT, List.of("hello"))))
                        .isInstanceOfSatisfying(AiProviderException.class,
                                error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.AI_DISABLED)));
    }

    private static AiChatCommand chatCommand() {
        return new AiChatCommand(AiCapability.AGENT,
                List.of(new AiPromptMessage(AiPromptRole.USER, "hello")), AiResponseMode.TEXT);
    }
}
