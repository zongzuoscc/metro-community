package cumt.zongzuo.community.ai.userprovider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cumt.zongzuo.community.ai.provider.AiChatCommand;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.ai.provider.AiProviderErrorReason;
import cumt.zongzuo.community.ai.provider.AiProviderException;
import cumt.zongzuo.community.ai.provider.AiResponseMode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 调用用户配置的 OpenAI 兼容 Chat Completions 接口。
 *
 * <p>客户端禁止重定向，端点在每次调用前重新解析和校验，响应只提取标准字段。
 * 请求头与 API Key 不进入日志、异常信息或返回对象。</p>
 */
public final class UserOpenAiCompatibleGateway {

    public interface HttpTransport {
        HttpResponse post(URI uri, List<InetAddress> approvedAddresses,
                          Map<String, String> headers, String body) throws Exception;
    }

    public record HttpResponse(int status, String body) { }

    private final AiProviderEndpointPolicy endpoints;
    private final HttpTransport transport;
    private final ObjectMapper mapper = new ObjectMapper();

    public UserOpenAiCompatibleGateway(AiProviderEndpointPolicy endpoints, HttpTransport transport) {
        this.endpoints = endpoints;
        this.transport = transport;
    }

    public AiChatResult generate(UserAiProviderRecord setting, String apiKey,
                                 AiChatCommand command) {
        AiProviderEndpointPolicy.ValidatedEndpoint validated =
                endpoints.validateAndResolve(setting.getBaseUrl());
        URI endpoint = URI.create(validated.normalizedBaseUrl() + "/chat/completions");
        ObjectNode request = mapper.createObjectNode().put("model", setting.getModel());
        ArrayNode messages = request.putArray("messages");
        command.messages().forEach(message -> messages.addObject()
                .put("role", message.role().name().toLowerCase(Locale.ROOT))
                .put("content", message.text()));
        if (command.responseMode() == AiResponseMode.JSON_OBJECT) {
            request.putObject("response_format").put("type", "json_object");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        try {
            HttpResponse response = transport.post(endpoint, validated.approvedAddresses(), headers,
                    mapper.writeValueAsString(request));
            if (response.status() < 200 || response.status() >= 300) {
                throw providerStatus(response.status());
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            String text = choice.path("message").path("content").asText("");
            String finishReason = choice.path("finish_reason").asText("").strip().toLowerCase(Locale.ROOT);
            String actualModel = root.path("model").asText(setting.getModel());
            if (text.isBlank() || finishReason.isBlank() || !setting.getModel().equals(actualModel)) {
                throw new AiProviderException(AiProviderErrorReason.MALFORMED_RESPONSE,
                        "AI provider returned an incompatible response");
            }
            long input = nonNegative(root.path("usage").path("prompt_tokens").asLong(0));
            long output = nonNegative(root.path("usage").path("completion_tokens").asLong(0));
            return new AiChatResult(text, finishReason, input, output,
                    setting.getProvider().toLowerCase(Locale.ROOT), actualModel);
        }
        catch (AiProviderException error) {
            throw error;
        }
        catch (Exception error) {
            throw new AiProviderException(AiProviderErrorReason.CONNECTION_FAILURE,
                    "User AI provider request failed", error);
        }
    }

    /**
     * 创建固定单次 DNS 结果的 HTTPS 传输。
     *
     * <p>URL 仍保留用户配置的原域名，因此 TLS SNI、证书主机名与 Host 头都按原域名校验；
     * 只有底层建连地址替换为安全策略已经检查过的公网 IP。禁用系统代理和重定向，避免请求
     * 绕过这一地址约束。这样 DNS 重绑定无法把第二次解析切到本机、内网或云元数据地址。</p>
     */
    public static HttpTransport pinnedTransport(Duration connectTimeout, Duration requestTimeout) {
        OkHttpClient baseClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout)
                .callTimeout(requestTimeout)
                .followRedirects(false)
                .followSslRedirects(false)
                .proxy(Proxy.NO_PROXY)
                .build();
        MediaType json = MediaType.get("application/json; charset=utf-8");
        return (uri, approvedAddresses, headers, body) -> {
            if (approvedAddresses == null || approvedAddresses.isEmpty()) {
                throw new UnknownHostException("No approved address is available");
            }
            String approvedHost = uri.getHost();
            OkHttpClient requestClient = baseClient.newBuilder().dns(hostname -> {
                if (!hostname.equalsIgnoreCase(approvedHost)) {
                    throw new UnknownHostException("Unexpected DNS lookup");
                }
                return List.copyOf(approvedAddresses);
            }).build();
            Request.Builder request = new Request.Builder().url(uri.toString())
                    .post(RequestBody.create(body, json));
            headers.forEach(request::header);
            try (Response response = requestClient.newCall(request.build()).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                return new HttpResponse(response.code(), responseBody);
            }
        };
    }

    private static AiProviderException providerStatus(int status) {
        AiProviderErrorReason reason = status == 429 ? AiProviderErrorReason.RATE_LIMITED
                : status >= 500 ? AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE
                : AiProviderErrorReason.NON_RETRYABLE_PROVIDER_FAILURE;
        return new AiProviderException(reason, status,
                "User AI provider returned HTTP status " + status, null);
    }

    private static long nonNegative(long value) {
        if (value < 0) {
            throw new AiProviderException(AiProviderErrorReason.MALFORMED_RESPONSE,
                    "AI provider returned a negative token count");
        }
        return value;
    }
}
