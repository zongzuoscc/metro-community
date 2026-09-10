package cumt.zongzuo.community.ai.provider;

import okhttp3.OkHttpClient;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.retry.support.RetryTemplate;

import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 标准 EmbeddingModel 编码协议；业务边界仍拒绝索引缺失、重复、维度漂移与非有限值。 */
public final class OpenAiCompatibleEmbeddingGateway implements EmbeddingGateway, EmbeddingModel {
    public interface HttpTransport {
        HttpResponse post(URI uri, Map<String, String> headers, String body) throws Exception;
    }

    public record HttpResponse(int status, String body) {}

    private final EmbeddingModel delegate;
    private final String provider;
    private final String model;
    private final int dimensions;

    public OpenAiCompatibleEmbeddingGateway(
            HttpTransport transport,
            String baseUrl,
            String key,
            String provider,
            String model,
            int dimensions) {
        String normalized = Objects.requireNonNull(baseUrl).strip().replaceAll("/+$", "");
        URI uri = URI.create(normalized);
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || !"https".equalsIgnoreCase(uri.getScheme()))
            throw new IllegalArgumentException("platform embedding baseUrl must be absolute HTTPS");
        if (dimensions < 1 || dimensions > 4096)
            throw new IllegalArgumentException("embedding dimensions are invalid");
        if (key == null
                || key.isBlank()
                || model == null
                || model.isBlank()
                || provider == null
                || provider.isBlank())
            throw new IllegalArgumentException("Required embedding setting is blank");
        this.provider = provider.strip().toLowerCase(Locale.ROOT);
        this.model = model.strip();
        this.dimensions = dimensions;
        var api =
                SpringAiModels.api(
                        normalized,
                        key,
                        SpringAiHttpTransport.requests(
                                (url, headers, body) -> {
                                    var response = transport.post(url, headers, body);
                                    return new SpringAiHttpTransport.Reply(
                                            response.status(), response.body());
                                }),
                        null);
        delegate =
                new OpenAiEmbeddingModel(
                        api,
                        MetadataMode.EMBED,
                        OpenAiEmbeddingOptions.builder()
                                .model(model)
                                .dimensions(dimensions)
                                .encodingFormat("float")
                                .build(),
                        RetryTemplate.builder().maxAttempts(1).build());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        try {
            var response = delegate.call(request);
            if (response == null
                    || response.getResults().size() != request.getInstructions().size())
                throw new AiProviderException(
                        AiProviderErrorReason.EMPTY_RESPONSE,
                        "AI provider returned no embedding result");
            var sorted =
                    response.getResults().stream()
                            .sorted(Comparator.comparingInt(Embedding::getIndex))
                            .toList();
            for (int i = 0; i < sorted.size(); i++) {
                var value = sorted.get(i);
                float[] vector = value.getOutput();
                if (value.getIndex() != i || vector == null || vector.length != dimensions)
                    throw malformed();
                for (float component : vector) if (!Float.isFinite(component)) throw malformed();
            }
            if (response.getMetadata().getUsage().getPromptTokens() < 0
                    || response.getMetadata().getUsage().getTotalTokens() < 0) throw malformed();
            return new EmbeddingResponse(sorted, response.getMetadata());
        } catch (RuntimeException error) {
            throw SpringAiModels.failure(error);
        }
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public EmbeddingResult embed(EmbeddingCommand command) {
        if (command.capability() != AiCapability.EMBEDDING)
            throw new IllegalArgumentException("Embedding capability required");
        var result =
                call(new EmbeddingRequest(command.inputs(), EmbeddingOptions.builder().build()));
        return new EmbeddingResult(
                result.getResults().stream().map(Embedding::getOutput).toList(),
                provider,
                model,
                result.getMetadata().getUsage().getTotalTokens());
    }

    public static HttpTransport httpTransport(Duration connectTimeout, Duration requestTimeout) {
        OkHttpClient client =
                SpringAiHttpTransport.clientBuilder()
                        .connectTimeout(connectTimeout)
                        .callTimeout(requestTimeout)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .proxy(Proxy.NO_PROXY)
                        .build();
        return (uri, headers, body) -> {
            var result = SpringAiHttpTransport.post(client, uri, headers, body);
            return new HttpResponse(result.status(), result.body());
        };
    }

    private static AiProviderException malformed() {
        return new AiProviderException(
                AiProviderErrorReason.MALFORMED_RESPONSE,
                "AI provider returned malformed embeddings");
    }
}
