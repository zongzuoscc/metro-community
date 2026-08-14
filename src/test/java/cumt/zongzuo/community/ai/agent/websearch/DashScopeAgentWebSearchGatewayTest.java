package cumt.zongzuo.community.ai.agent.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定百炼 DashScope 联网搜索的官方请求结构，以及前端可追溯来源所需的响应字段。
 */
class DashScopeAgentWebSearchGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void forcesSearchAndReturnsBoundedTraceableSources() throws Exception {
        AtomicReference<URI> endpoint = new AtomicReference<>();
        AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        DashScopeAgentWebSearchGateway.HttpTransport transport = (uri, requestHeaders, requestBody) -> {
            endpoint.set(uri);
            headers.set(requestHeaders);
            body.set(requestBody);
            return new DashScopeAgentWebSearchGateway.HttpResponse(200, """
                    {
                      "output": {
                        "choices": [{
                          "message": {"content": "杭州今天有降雨，出行前请关注临近预报。"},
                          "finish_reason": "stop"
                        }],
                        "search_info": {
                          "search_results": [
                            {"index": 1, "title": "杭州天气预报", "url": "https://example.com/weather", "site_name": "示例气象站"},
                            {"index": 2, "title": "非法协议不会下发", "url": "javascript:alert(1)", "site_name": "坏来源"}
                          ]
                        }
                      }
                    }
                    """);
        };
        DashScopeAgentWebSearchGateway gateway = new DashScopeAgentWebSearchGateway(
                "https://workspace.cn-beijing.maas.aliyuncs.com/api/v1",
                "secret-key", "qwen-plus", "turbo", 8, transport);

        AgentWebSearchResult result = gateway.search("杭州今天会下雨吗",
                Instant.now().plusSeconds(30));

        assertThat(endpoint.get().toString()).isEqualTo(
                "https://workspace.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/text-generation/generation");
        assertThat(headers.get()).containsEntry("Authorization", "Bearer secret-key");
        JsonNode request = mapper.readTree(body.get());
        assertThat(request.path("model").asText()).isEqualTo("qwen-plus");
        assertThat(request.path("parameters").path("enable_search").asBoolean()).isTrue();
        assertThat(request.path("parameters").path("search_options").path("forced_search").asBoolean()).isTrue();
        assertThat(request.path("parameters").path("search_options").path("enable_source").asBoolean()).isTrue();
        assertThat(request.path("parameters").path("search_options").path("search_strategy").asText())
                .isEqualTo("turbo");
        assertThat(result.summary()).contains("杭州今天有降雨");
        assertThat(result.sources()).containsExactly(new AgentWebSource(1, "杭州天气预报",
                "https://example.com/weather", "示例气象站"));
    }
}
