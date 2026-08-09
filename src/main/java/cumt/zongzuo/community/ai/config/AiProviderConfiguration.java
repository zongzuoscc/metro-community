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

        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(deepSeek.getBaseUrl())
                .apiKey(deepSeek.getApiKey())
                .restClientBuilder(jdkRestClientBuilder())
                .webClientBuilder(jdkWebClientBuilder())
                .responseErrorHandler(statusOnlyErrorHandler())
                .build();
        DeepSeekChatModel model = DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(DeepSeekChatOptions.builder().model(deepSeek.getModel()).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
        return new DeepSeekAiChatGateway(model, deepSeek.getModel(), enabledChatCapabilities(properties));
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

        OllamaApi api = OllamaApi.builder()
                .baseUrl(ollama.getBaseUrl())
                .restClientBuilder(jdkRestClientBuilder())
                .webClientBuilder(jdkWebClientBuilder())
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

    private static WebClient.Builder jdkWebClientBuilder() {
        return WebClient.builder().clientConnector(new JdkClientHttpConnector());
    }

    private static RestClient.Builder jdkRestClientBuilder() {
        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newHttpClient()));
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
}
