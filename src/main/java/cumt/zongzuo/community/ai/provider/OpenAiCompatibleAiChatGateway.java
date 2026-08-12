package cumt.zongzuo.community.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 调用平台统一配置的 OpenAI 兼容 Chat Completions 接口。
 *
 * <p>平台配置由后端环境变量提供，因此这里不会读取浏览器参数，也不会把地址、请求头或
 * API Key 放入返回对象和异常正文。不同能力使用各自的传输实例，以便摘要、写作、审核等
 * 请求继续遵守各自的超时上限。</p>
 */
public final class OpenAiCompatibleAiChatGateway implements AiChatGateway {

    public interface HttpTransport {
        HttpResponse post(URI uri, Map<String, String> headers, String body) throws Exception;
    }

    public record HttpResponse(int status, String body) { }

    private final Map<AiCapability, HttpTransport> transports;
    private final URI chatCompletionsEndpoint;
    private final String apiKey;
    private final String provider;
    private final String model;
    private final int moderationMaxOutputTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleAiChatGateway(Map<AiCapability, HttpTransport> transports,
                                         String baseUrl, String apiKey, String provider,
                                         String model, int moderationMaxOutputTokens) {
        Objects.requireNonNull(transports, "transports must not be null");
        EnumMap<AiCapability, HttpTransport> copy = new EnumMap<>(AiCapability.class);
        copy.putAll(transports);
        this.transports = Collections.unmodifiableMap(copy);
        this.chatCompletionsEndpoint = endpoint(baseUrl);
        this.apiKey = requireText(apiKey, "apiKey");
        this.provider = requireText(provider, "provider").toLowerCase(Locale.ROOT);
        this.model = requireText(model, "model");
        if (moderationMaxOutputTokens <= 0) {
            throw new IllegalArgumentException("moderationMaxOutputTokens must be positive");
        }
        this.moderationMaxOutputTokens = moderationMaxOutputTokens;
    }

    @Override
    public AiChatResult generate(AiChatCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        HttpTransport transport = transports.get(command.capability());
        if (transport == null) {
            throw new AiProviderException(AiProviderErrorReason.AI_DISABLED,
                    "AI capability is disabled");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        try {
            HttpResponse response = transport.post(chatCompletionsEndpoint, headers,
                    mapper.writeValueAsString(request(command)));
            if (response.status() < 200 || response.status() >= 300) {
                throw AiProviderException.fromHttpStatus(
                        new ProviderHttpStatusException(response.status()));
            }
            return parse(response.body());
        }
        catch (AiProviderException error) {
            throw error;
        }
        catch (Exception error) {
            throw AiProviderException.fromTransport(error);
        }
    }

    private ObjectNode request(AiChatCommand command) {
        ObjectNode request = mapper.createObjectNode().put("model", model).put("stream", false);
        ArrayNode messages = request.putArray("messages");
        command.messages().forEach(message -> messages.addObject()
                .put("role", message.role().name().toLowerCase(Locale.ROOT))
                .put("content", message.text()));
        if (command.responseMode() == AiResponseMode.JSON_OBJECT) {
            request.putObject("response_format").put("type", "json_object");
        }
        // 审核结果会直接影响文章可见性，因此保持确定性的 temperature=0，
        // 同时强制输出上限，避免供应商默认值绕过整任务 token 与成本预算。
        if (command.capability() == AiCapability.MODERATION) {
            request.put("temperature", 0.0);
            request.put("max_tokens", moderationMaxOutputTokens);
        }
        return request;
    }

    private AiChatResult parse(String body) {
        if (body == null || body.isBlank()) {
            throw emptyResponse();
        }
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode choices = root == null ? null : root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw emptyResponse();
            }
            JsonNode choice = choices.get(0);
            String text = choice.path("message").path("content").asText("");
            if (text.isBlank()) {
                throw emptyResponse();
            }
            String finishReason = choice.path("finish_reason").asText("")
                    .strip().toLowerCase(Locale.ROOT);
            if (finishReason.isBlank()) {
                throw malformed("AI provider finish reason was missing", null);
            }
            long inputTokens = nonNegative(root.path("usage").path("prompt_tokens").asLong(0));
            long outputTokens = nonNegative(root.path("usage").path("completion_tokens").asLong(0));
            return new AiChatResult(text, finishReason, inputTokens, outputTokens, provider, model);
        }
        catch (AiProviderException error) {
            throw error;
        }
        catch (Exception error) {
            throw malformed("AI provider returned a malformed response", error);
        }
    }

    /**
     * 创建不使用系统代理、也不跟随重定向的传输。
     *
     * <p>平台地址虽然由运维人员配置而不是用户输入，仍禁用代理和重定向，避免 API Key
     * 被意外转发到第二个地址。每种能力传入不同请求超时，因此慢摘要不会放宽审核超时。</p>
     */
    public static HttpTransport httpTransport(Duration connectTimeout, Duration requestTimeout) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout)
                .callTimeout(requestTimeout)
                .followRedirects(false)
                .followSslRedirects(false)
                .proxy(Proxy.NO_PROXY)
                .build();
        MediaType json = MediaType.get("application/json; charset=utf-8");
        return (uri, headers, body) -> {
            Request.Builder request = new Request.Builder().url(uri.toString())
                    .post(RequestBody.create(body, json));
            headers.forEach(request::header);
            try (Response response = client.newCall(request.build()).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                return new HttpResponse(response.code(), responseBody);
            }
        };
    }

    private static URI endpoint(String baseUrl) {
        String normalized = requireText(baseUrl, "baseUrl").replaceAll("/+$", "");
        URI base = URI.create(normalized);
        if (base.getHost() == null || base.getUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl must be an absolute HTTPS URL");
        }
        boolean https = "https".equalsIgnoreCase(base.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(base.getScheme())
                && ("127.0.0.1".equals(base.getHost()) || "localhost".equalsIgnoreCase(base.getHost())
                || "::1".equals(base.getHost()));
        // 只有本机契约测试和隔离开发 stub 可以使用明文 HTTP。任何远端平台地址都必须
        // 使用 HTTPS，否则 Bearer API Key 会在网络链路上以明文形式发送。
        if (!https && !loopbackHttp) {
            throw new IllegalArgumentException("Non-loopback platform baseUrl must use HTTPS");
        }
        return URI.create(normalized + "/chat/completions");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    private static long nonNegative(long value) {
        if (value < 0) {
            throw malformed("AI provider returned a negative token count", null);
        }
        return value;
    }

    private static AiProviderException emptyResponse() {
        return new AiProviderException(AiProviderErrorReason.EMPTY_RESPONSE,
                "AI provider returned no chat result");
    }

    private static AiProviderException malformed(String message, Throwable cause) {
        return new AiProviderException(AiProviderErrorReason.MALFORMED_RESPONSE, message, cause);
    }
}
