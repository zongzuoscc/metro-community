package cumt.zongzuo.community;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchUpgradeIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RestClient restClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void serverAndIkPluginRunOnThePinnedSupportedAxis() throws Exception {
        var root = json(new Request("GET", "/"));
        assertThat(root.path("version").path("number").asText()).isEqualTo("8.18.1");

        var plugins = json(new Request("GET", "/_cat/plugins?format=json"));
        assertThat(plugins).anySatisfy(plugin -> {
            assertThat(plugin.path("component").asText()).isEqualTo("analysis-ik");
            assertThat(plugin.path("version").asText()).isEqualTo("8.18.1");
        });

        Request analyze = new Request("POST", "/_analyze");
        analyze.setEntity(new NStringEntity(
                "{\"analyzer\":\"ik_smart\",\"text\":\"中文知识检索系统\"}",
                ContentType.APPLICATION_JSON));
        assertThat(json(analyze).path("tokens")).isNotEmpty();
    }

    private com.fasterxml.jackson.databind.JsonNode json(Request request) throws Exception {
        return objectMapper.readTree(EntityUtils.toString(restClient.performRequest(request).getEntity()));
    }
}
