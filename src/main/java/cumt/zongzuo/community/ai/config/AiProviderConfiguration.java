package cumt.zongzuo.community.ai.config;

import cumt.zongzuo.community.ai.provider.AiChatGateway;
import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiProviderErrorReason;
import cumt.zongzuo.community.ai.provider.DeepSeekAiChatGateway;
import cumt.zongzuo.community.ai.provider.DisabledAiChatGateway;
import cumt.zongzuo.community.ai.provider.DisabledEmbeddingGateway;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import cumt.zongzuo.community.ai.provider.OllamaEmbeddingGateway;
import cumt.zongzuo.community.ai.provider.ProviderHttpStatusException;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MetroAiProperties.class)
public class AiProviderConfiguration {

    @Bean
    AiChatGateway aiChatGateway(MetroAiProperties properties) {
        if (!properties.isEnabled() || !properties.anyChatCapabilityEnabled()) {
            return new DisabledAiChatGateway(AiProviderErrorReason.AI_DISABLED);
        }

        MetroAiProperties.DeepSeekProperties deepSeek = properties.getDeepSeek();
        if (!StringUtils.hasText(deepSeek.getBaseUrl())
                || !StringUtils.hasText(deepSeek.getApiKey())
                || !StringUtils.hasText(deepSeek.getModel())) {
            return new DisabledAiChatGateway(AiProviderErrorReason.AI_UNAVAILABLE);
        }

        EnumMap<AiCapability, DeepSeekChatModel> models = new EnumMap<>(AiCapability.class);
        for (AiCapability capability : enabledChatCapabilities(properties)) {
            Duration readTimeout = providerReadTimeout(capabilityTimeout(properties, capability),
                    properties.getRuntime());
            models.put(capability, deepSeekChatModel(deepSeek,
                    properties.getRuntime().getProviderConnectTimeout(), readTimeout));
        }
        return new DeepSeekAiChatGateway(models, deepSeek.getModel());
    }

    @Bean
    EmbeddingGateway embeddingGateway(MetroAiProperties properties) {
        if (!properties.isCapabilityEnabled(AiCapability.EMBEDDING)) {
            return new DisabledEmbeddingGateway(AiProviderErrorReason.AI_DISABLED);
        }

        MetroAiProperties.OllamaProperties ollama = properties.getOllama();
        if (!StringUtils.hasText(ollama.getBaseUrl()) || !StringUtils.hasText(ollama.getModel())) {
            return new DisabledEmbeddingGateway(AiProviderErrorReason.AI_UNAVAILABLE);
        }

        Duration readTimeout = providerReadTimeout(properties.getEmbedding().getTimeout(),
                properties.getRuntime());
        OllamaApi api = OllamaApi.builder()
                .baseUrl(ollama.getBaseUrl())
                .restClientBuilder(jdkRestClientBuilder(
                        properties.getRuntime().getProviderConnectTimeout(), readTimeout))
                .webClientBuilder(jdkWebClientBuilder(
                        properties.getRuntime().getProviderConnectTimeout(), readTimeout))
                .responseErrorHandler(statusOnlyErrorHandler())
                .build();
        OllamaEmbeddingModel model = OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .defaultOptions(OllamaEmbeddingOptions.builder().model(ollama.getModel()).build())
                .modelManagementOptions(ModelManagementOptions.defaults())
                .build();
        return new OllamaEmbeddingGateway(model, ollama.getModel());
    }

    private static ResponseErrorHandler statusOnlyErrorHandler() {
        return new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return response.getStatusCode().isError();
            }

            @Override
            public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
                throw new ProviderHttpStatusException(response.getStatusCode().value());
            }
        };
    }

    private static DeepSeekChatModel deepSeekChatModel(MetroAiProperties.DeepSeekProperties properties,
                                                       Duration connectTimeout, Duration readTimeout) {
        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .restClientBuilder(jdkRestClientBuilder(connectTimeout, readTimeout))
                .webClientBuilder(jdkWebClientBuilder(connectTimeout, readTimeout))
                .responseErrorHandler(statusOnlyErrorHandler())
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(DeepSeekChatOptions.builder().model(properties.getModel()).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
    }

    private static WebClient.Builder jdkWebClientBuilder(Duration connectTimeout, Duration readTimeout) {
        HttpClient client = jdkHttpClient(connectTimeout);
        JdkClientHttpConnector connector = new JdkClientHttpConnector(client);
        connector.setReadTimeout(readTimeout);
        return WebClient.builder().clientConnector(connector);
    }

    private static RestClient.Builder jdkRestClientBuilder(Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                jdkHttpClient(connectTimeout));
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory);
    }

    private static HttpClient jdkHttpClient(Duration connectTimeout) {
        requirePositive(connectTimeout, "metro.ai.runtime.provider-connect-timeout");
        return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    private static Duration providerReadTimeout(Duration capabilityTimeout,
                                                MetroAiProperties.RuntimeProperties runtime) {
        requirePositive(capabilityTimeout, "capability timeout");
        Duration connectTimeout = runtime.getProviderConnectTimeout();
        Duration margin = runtime.getProviderTimeoutMargin();
        requirePositive(connectTimeout, "metro.ai.runtime.provider-connect-timeout");
        requirePositive(margin, "metro.ai.runtime.provider-timeout-margin");
        if (connectTimeout.compareTo(capabilityTimeout) >= 0) {
            throw new IllegalStateException("Provider connect timeout must be shorter than capability timeout");
        }
        if (margin.compareTo(capabilityTimeout) >= 0) {
            throw new IllegalStateException("Provider timeout margin must be shorter than capability timeout");
        }
        return capabilityTimeout.minus(margin);
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(property + " must be positive");
        }
    }

    private static Set<AiCapability> enabledChatCapabilities(MetroAiProperties properties) {
        EnumSet<AiCapability> capabilities = EnumSet.noneOf(AiCapability.class);
        for (AiCapability capability : AiCapability.values()) {
            if (capability != AiCapability.EMBEDDING && properties.isCapabilityEnabled(capability)) {
                capabilities.add(capability);
            }
        }
        return Set.copyOf(capabilities);
    }

    private static Duration capabilityTimeout(MetroAiProperties properties, AiCapability capability) {
        return switch (capability) {
            case AGENT -> properties.getAgent().getTimeout();
            case ARTICLE_SUMMARY -> properties.getArticleSummary().getTimeout();
            case WRITING -> properties.getWriting().getTimeout();
            case HYDE -> properties.getHyde().getTimeout();
            case MODERATION -> properties.getModeration().getTimeout();
            case MEMORY_EXTRACTION -> properties.getMemory().getTimeout();
            case EMBEDDING -> properties.getEmbedding().getTimeout();
        };
    }
}
