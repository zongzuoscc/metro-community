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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 调用百炼等平台提供的 OpenAI 兼容 Embeddings 接口。
 *
 * <p>该网关与聊天网关共用后端环境变量中的平台地址和 API Key，但使用独立模型名。
 * 请求固定 dimensions 和 float 编码，响应按 index 恢复输入顺序，并拒绝缺项、重复项、
 * 非有限值及维度漂移，防止错误向量悄悄污染语义排序。</p>
 */
public final class OpenAiCompatibleEmbeddingGateway implements EmbeddingGateway {

    public interface HttpTransport {
        HttpResponse post(URI uri, Map<String, String> headers, String body) throws Exception;
    }

    public record HttpResponse(int status, String body) {
    }

    private final HttpTransport transport;
    private final URI endpoint;
    private final String apiKey;
    private final String provider;
    private final String model;
    private final int dimensions;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleEmbeddingGateway(HttpTransport transport, String baseUrl,
                                             String apiKey, String provider, String model,
                                             int dimensions) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpoint = endpoint(baseUrl);
        this.apiKey = requireText(apiKey, "apiKey");
        this.provider = requireText(provider, "provider").toLowerCase(Locale.ROOT);
        this.model = requireText(model, "model");
        if (dimensions < 1 || dimensions > 4_096) {
            throw new IllegalArgumentException("embedding dimensions are invalid");
        }
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResult embed(EmbeddingCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.capability() != AiCapability.EMBEDDING) {
            throw new IllegalArgumentException("OpenAI-compatible embedding only accepts EMBEDDING");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        try {
            HttpResponse response = transport.post(endpoint, headers,
                    mapper.writeValueAsString(request(command.inputs())));
            if (response.status() < 200 || response.status() >= 300) {
                throw AiProviderException.fromHttpStatus(new ProviderHttpStatusException(response.status()));
            }
            return parse(response.body(), command.inputs().size());
        } catch (AiProviderException error) {
            throw error;
        } catch (Exception error) {
            throw AiProviderException.fromTransport(error);
        }
    }

    private ObjectNode request(List<String> inputs) {
        ObjectNode request = mapper.createObjectNode().put("model", model)
                .put("dimensions", dimensions).put("encoding_format", "float");
        ArrayNode input = request.putArray("input");
        inputs.forEach(input::add);
        return request;
    }

    private EmbeddingResult parse(String body, int expectedCount) {
        if (body == null || body.isBlank()) throw empty();
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode data = root == null ? null : root.get("data");
            if (data == null || !data.isArray() || data.size() != expectedCount) throw empty();
            List<IndexedVector> indexed = new ArrayList<>(expectedCount);
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                JsonNode values = item.get("embedding");
                if (index < 0 || index >= expectedCount || values == null || !values.isArray()
                        || values.size() != dimensions) {
                    throw malformed(null);
                }
                float[] vector = new float[dimensions];
                for (int position = 0; position < dimensions; position++) {
                    if (!values.get(position).isNumber()) throw malformed(null);
                    vector[position] = values.get(position).floatValue();
                    if (!Float.isFinite(vector[position])) throw malformed(null);
                }
                indexed.add(new IndexedVector(index, vector));
            }
            indexed.sort(Comparator.comparingInt(IndexedVector::index));
            for (int index = 0; index < indexed.size(); index++) {
                if (indexed.get(index).index() != index) throw malformed(null);
            }
            return new EmbeddingResult(indexed.stream().map(IndexedVector::vector).toList(),
                    provider, model);
        } catch (AiProviderException error) {
            throw error;
        } catch (Exception error) {
            throw malformed(error);
        }
    }

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

    private static URI endpoint(String baseUrl) {
        String normalized = requireText(baseUrl, "baseUrl").replaceAll("/+$", "");
        URI base = URI.create(normalized);
        if (base.getHost() == null || base.getUserInfo() != null
                || !"https".equalsIgnoreCase(base.getScheme())) {
            throw new IllegalArgumentException("platform embedding baseUrl must be absolute HTTPS");
        }
        return URI.create(normalized + "/embeddings");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is blank");
        return value.strip();
    }

    private static AiProviderException empty() {
        return new AiProviderException(AiProviderErrorReason.EMPTY_RESPONSE,
                "AI provider returned no embedding result");
    }

    private static AiProviderException malformed(Throwable cause) {
        return new AiProviderException(AiProviderErrorReason.MALFORMED_RESPONSE,
                "AI provider returned malformed embeddings", cause);
    }

    private record IndexedVector(int index, float[] vector) {
    }
}
