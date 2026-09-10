package cumt.zongzuo.community.ai.provider;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.client.reactive.ClientHttpConnector;

import reactor.core.publisher.Flux;

import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 平台模型使用标准 OpenAiChatModel，HTTP 适配只维护原有安全连接边界。 */
public final class OpenAiCompatibleAiChatGateway implements AiChatGateway, ChatModel {
    public interface HttpTransport {
        HttpResponse post(URI uri, Map<String, String> headers, String body) throws Exception;

        default ClientHttpConnector connector() {
            return null;
        }
    }

    public record HttpResponse(int status, String body) {}

    private final Map<AiCapability, ChatModel> models;
    private final String provider;
    private final String model;
    private final int moderationLimit;

    public OpenAiCompatibleAiChatGateway(
            Map<AiCapability, HttpTransport> transports,
            String baseUrl,
            String apiKey,
            String provider,
            String model,
            int moderationLimit) {
        String normalized = required(baseUrl).replaceAll("/+$", "");
        URI base = URI.create(normalized);
        boolean local =
                Set.of("127.0.0.1", "localhost", "::1").contains(String.valueOf(base.getHost()));
        if (base.getHost() == null
                || base.getUserInfo() != null
                || !("https".equals(base.getScheme()) || "http".equals(base.getScheme()) && local))
            throw new IllegalArgumentException("baseUrl must use HTTPS");
        this.provider = required(provider).toLowerCase(Locale.ROOT);
        this.model = required(model);
        if (moderationLimit <= 0)
            throw new IllegalArgumentException("moderationMaxOutputTokens must be positive");
        this.moderationLimit = moderationLimit;
        var values = new EnumMap<AiCapability, ChatModel>(AiCapability.class);
        transports.forEach(
                (capability, transport) ->
                        values.put(
                                capability,
                                SpringAiModels.chat(
                                        SpringAiModels.api(
                                                normalized,
                                                required(apiKey),
                                                SpringAiHttpTransport.requests(
                                                        (uri, headers, body) -> {
                                                            var result =
                                                                    transport.post(
                                                                            uri, headers, body);
                                                            return new SpringAiHttpTransport.Reply(
                                                                    result.status(), result.body());
                                                        }),
                                                transport.connector()),
                                        model)));
        this.models = Map.copyOf(values);
    }

    private ChatModel model(AiCapability capability) {
        ChatModel value = models.get(capability);
        if (value == null)
            throw new AiProviderException(
                    AiProviderErrorReason.AI_DISABLED, "AI capability is disabled");
        return value;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            return SpringAiModels.validate(model(AiCapability.AGENT).call(prompt), model, false);
        } catch (RuntimeException error) {
            throw SpringAiModels.failure(error);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return model(AiCapability.AGENT).stream(prompt).onErrorMap(SpringAiModels::failure);
    }

    @Override
    public AiChatResult generate(AiChatCommand command) {
        try {
            return SpringAiModels.result(
                    model(command.capability())
                            .call(SpringAiModels.prompt(command, moderationLimit)),
                    provider,
                    model,
                    false);
        } catch (RuntimeException error) {
            throw SpringAiModels.failure(error);
        }
    }

    @Override
    public AiChatResult stream(AiChatCommand command, AiStreamObserver observer) {
        return SpringAiModels.stream(
                model(command.capability()).stream(
                        SpringAiModels.streamingPrompt(command, moderationLimit)),
                provider,
                model,
                true,
                observer);
    }

    public static HttpTransport httpTransport(Duration connectTimeout, Duration requestTimeout) {
        var client =
                SpringAiHttpTransport.clientBuilder()
                        .connectTimeout(connectTimeout)
                        .callTimeout(requestTimeout)
                        .readTimeout(requestTimeout)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .proxy(Proxy.NO_PROXY)
                        .build();
        return new HttpTransport() {
            public HttpResponse post(URI uri, Map<String, String> headers, String body)
                    throws Exception {
                var result = SpringAiHttpTransport.post(client, uri, headers, body);
                return new HttpResponse(result.status(), result.body());
            }

            public ClientHttpConnector connector() {
                return new SpringAiHttpTransport(uri -> client);
            }
        };
    }

    private static String required(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Required provider setting is blank");
        return value.strip();
    }
}
