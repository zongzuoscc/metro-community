package cumt.zongzuo.community.ai.userprovider;

import cumt.zongzuo.community.ai.provider.AiChatCommand;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.ai.provider.AiStreamObserver;
import cumt.zongzuo.community.ai.provider.SpringAiHttpTransport;
import cumt.zongzuo.community.ai.provider.SpringAiModels;

import okhttp3.OkHttpClient;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.client.reactive.ClientHttpConnector;

import reactor.core.publisher.Flux;

import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** BYOK 每次调用重验端点并固定 DNS，协议和流式解码由 OpenAiChatModel 负责。 */
public final class UserOpenAiCompatibleGateway {
    public interface HttpTransport {
        HttpResponse post(
                URI uri, List<InetAddress> addresses, Map<String, String> headers, String body)
                throws Exception;

        default ClientHttpConnector connector(URI uri, List<InetAddress> addresses) {
            return null;
        }
    }

    public record HttpResponse(int status, String body) {}

    private final AiProviderEndpointPolicy endpoints;
    private final HttpTransport transport;

    public UserOpenAiCompatibleGateway(
            AiProviderEndpointPolicy endpoints, HttpTransport transport) {
        this.endpoints = endpoints;
        this.transport = transport;
    }

    private ChatModel resolved(UserAiProviderRecord setting, String key) {
        var validated = endpoints.validateAndResolve(setting.getBaseUrl());
        var uri = URI.create(validated.normalizedBaseUrl() + "/chat/completions");
        return SpringAiModels.chat(
                SpringAiModels.api(
                        validated.normalizedBaseUrl(),
                        key,
                        SpringAiHttpTransport.requests(
                                (url, headers, body) -> {
                                    var response =
                                            transport.post(
                                                    url,
                                                    validated.approvedAddresses(),
                                                    headers,
                                                    body);
                                    return new SpringAiHttpTransport.Reply(
                                            response.status(), response.body());
                                }),
                        transport.connector(uri, validated.approvedAddresses())),
                setting.getModel());
    }

    public ChatModel model(UserAiProviderRecord setting, java.util.function.Supplier<String> key) {
        return new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                try {
                    return SpringAiModels.validate(
                            resolved(setting, key.get()).call(prompt), setting.getModel(), true);
                } catch (RuntimeException error) {
                    throw SpringAiModels.failure(error);
                }
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> resolved(setting, key.get()).stream(prompt))
                        .onErrorMap(SpringAiModels::failure);
            }
        };
    }

    public AiChatResult generate(UserAiProviderRecord setting, String key, AiChatCommand command) {
        return SpringAiModels.result(
                model(setting, () -> key).call(SpringAiModels.prompt(command, 0)),
                setting.getProvider().toLowerCase(Locale.ROOT),
                setting.getModel(),
                true);
    }

    public AiChatResult stream(
            UserAiProviderRecord setting,
            String key,
            AiChatCommand command,
            AiStreamObserver observer) {
        return SpringAiModels.stream(
                model(setting, () -> key).stream(SpringAiModels.streamingPrompt(command, 0)),
                setting.getProvider().toLowerCase(Locale.ROOT),
                setting.getModel(),
                true,
                observer);
    }

    public static HttpTransport pinnedTransport(Duration connectTimeout, Duration requestTimeout) {
        var base =
                SpringAiHttpTransport.clientBuilder()
                        .connectTimeout(connectTimeout)
                        .callTimeout(requestTimeout)
                        .readTimeout(requestTimeout)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .proxy(Proxy.NO_PROXY)
                        .build();
        return new HttpTransport() {
            private OkHttpClient client(URI uri, List<InetAddress> addresses) {
                if (addresses == null || addresses.isEmpty())
                    throw new IllegalArgumentException("No approved address");
                List<InetAddress> frozen = List.copyOf(addresses);
                // URL/Host/SNI 保留原域名；只有连接 IP 被替换，禁止重新解析与代理绕行。
                return base.newBuilder()
                        .dns(
                                host -> {
                                    if (!host.equalsIgnoreCase(uri.getHost()))
                                        throw new UnknownHostException("Unexpected DNS lookup");
                                    return frozen;
                                })
                        .build();
            }

            public HttpResponse post(
                    URI uri, List<InetAddress> addresses, Map<String, String> headers, String body)
                    throws Exception {
                var response =
                        SpringAiHttpTransport.post(client(uri, addresses), uri, headers, body);
                return new HttpResponse(response.status(), response.body());
            }

            public ClientHttpConnector connector(URI uri, List<InetAddress> addresses) {
                var client = client(uri, addresses);
                return new SpringAiHttpTransport(
                        url -> {
                            if (!url.getHost().equalsIgnoreCase(uri.getHost()))
                                throw new IllegalArgumentException("Unexpected host");
                            return client;
                        });
            }
        };
    }
}
