package cumt.zongzuo.community.ai.agent.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cumt.zongzuo.community.ai.provider.AiProviderErrorReason;
import cumt.zongzuo.community.ai.provider.AiProviderException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 使用百炼 DashScope Generation 协议执行联网搜索。
 *
 * <p>OpenAI 兼容接口可以开启搜索，却不会返回来源列表；因此这里仅对搜索步骤使用
 * DashScope 协议，并把来源交给现有 Agent 生成链。请求强制搜索，确保用户开启开关后
 * 即使站内已经命中资料也仍会联网。</p>
 */
public final class DashScopeAgentWebSearchGateway implements AgentWebSearchGateway {

    private static final int MAX_SUMMARY_CHARACTERS = 12_000;
    private static final Set<String> SAFE_SCHEMES = Set.of("http", "https");

    public interface HttpTransport {
        HttpResponse post(URI uri, Map<String, String> headers, String body) throws Exception;
    }

    public record HttpResponse(int status, String body) { }

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final String strategy;
    private final int maxSources;
    private final HttpTransport transport;
    private final ObjectMapper mapper = new ObjectMapper();

    public DashScopeAgentWebSearchGateway(String baseUrl, String apiKey, String model,
                                          String strategy, int maxSources,
                                          HttpTransport transport) {
        String normalized = requireText(baseUrl, "baseUrl").replaceAll("/+$", "");
        URI base = URI.create(normalized);
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null) {
            throw new IllegalArgumentException("DashScope web search baseUrl must use HTTPS");
        }
        this.endpoint = URI.create(normalized + "/services/aigc/text-generation/generation");
        this.apiKey = requireText(apiKey, "apiKey");
        this.model = requireText(model, "model");
        this.strategy = requireText(strategy, "strategy");
        if (maxSources < 1 || maxSources > 20) {
            throw new IllegalArgumentException("maxSources must be between 1 and 20");
        }
        this.maxSources = maxSources;
        this.transport = transport;
    }

    @Override
    public AgentWebSearchResult search(String query, Instant deadline) {
        String normalizedQuery = requireText(query, "query");
        if (deadline == null || !deadline.isAfter(Instant.now())) {
            throw new AiProviderException(AiProviderErrorReason.TIMEOUT,
                    "Web search deadline has expired");
        }
        ObjectNode root = mapper.createObjectNode().put("model", model);
        ArrayNode messages = root.putObject("input").putArray("messages");
        messages.addObject().put("role", "user").put("content", normalizedQuery);
        ObjectNode parameters = root.putObject("parameters").put("enable_search", true)
                .put("result_format", "message");
        parameters.putObject("search_options").put("forced_search", true)
                .put("enable_source", true).put("enable_citation", true)
                .put("citation_format", "[ref_<number>]")
                .put("search_strategy", strategy);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        try {
            HttpResponse response = transport.post(endpoint, headers,
                    mapper.writeValueAsString(root));
            if (response.status() < 200 || response.status() >= 300) {
                throw providerStatus(response.status());
            }
            return parse(response.body());
        } catch (AiProviderException error) {
            throw error;
        } catch (Exception error) {
            throw new AiProviderException(AiProviderErrorReason.CONNECTION_FAILURE,
                    "Web search request failed", error);
        }
    }

    private AgentWebSearchResult parse(String body) throws Exception {
        JsonNode output = mapper.readTree(body).path("output");
        String summary = output.path("choices").path(0).path("message")
                .path("content").asText("").strip();
        String finishReason = output.path("choices").path(0).path("finish_reason")
                .asText("").strip();
        if (summary.isBlank() || summary.length() > MAX_SUMMARY_CHARACTERS
                || !"stop".equals(finishReason)) {
            throw new AiProviderException(AiProviderErrorReason.MALFORMED_RESPONSE,
                    "Web search returned an incomplete response");
        }
        List<AgentWebSource> sources = new ArrayList<>();
        for (JsonNode item : output.path("search_info").path("search_results")) {
            if (sources.size() >= maxSources) break;
            int index = item.path("index").asInt(0);
            String title = item.path("title").asText("").strip();
            String url = item.path("url").asText("").strip();
            String siteName = item.path("site_name").asText("").strip();
            if (index <= 0 || index > 99 || title.isBlank() || title.length() > 300
                    || siteName.length() > 160 || !safeUrl(url)) {
                continue;
            }
            sources.add(new AgentWebSource(index, title, url, siteName));
        }
        // 百炼的角标使用 ref_ 前缀；内部统一改为 W，避免与站内文章数字角标冲突。
        return new AgentWebSearchResult(summary.replaceAll("\\[ref_(\\d{1,2})]", "[W$1]"),
                sources);
    }

    private static boolean safeUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && uri.getUserInfo() == null
                    && SAFE_SCHEMES.contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 创建禁用代理与重定向的 HTTPS 传输，避免平台密钥被转发到第二个地址。 */
    public static HttpTransport httpTransport(Duration connectTimeout, Duration requestTimeout) {
        OkHttpClient client = new OkHttpClient.Builder().connectTimeout(connectTimeout)
                .callTimeout(requestTimeout).followRedirects(false).followSslRedirects(false)
                .proxy(Proxy.NO_PROXY).build();
        MediaType json = MediaType.get("application/json; charset=utf-8");
        return (uri, headers, body) -> {
            Request.Builder request = new Request.Builder().url(uri.toString())
                    .post(RequestBody.create(body, json));
            headers.forEach(request::header);
            try (Response response = client.newCall(request.build()).execute()) {
                return new HttpResponse(response.code(),
                        response.body() == null ? "" : response.body().string());
            }
        };
    }

    private static AiProviderException providerStatus(int status) {
        AiProviderErrorReason reason = status == 429 ? AiProviderErrorReason.RATE_LIMITED
                : status >= 500 ? AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE
                : AiProviderErrorReason.NON_RETRYABLE_PROVIDER_FAILURE;
        return new AiProviderException(reason, status,
                "Web search returned HTTP status " + status, null);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is blank");
        return value.strip();
    }
}
