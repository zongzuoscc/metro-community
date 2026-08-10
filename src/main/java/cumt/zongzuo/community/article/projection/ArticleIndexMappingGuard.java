package cumt.zongzuo.community.article.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;

@Component
public class ArticleIndexMappingGuard {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ArticleProjectionProperties properties;

    public ArticleIndexMappingGuard(RestClient restClient,
                                    ObjectMapper objectMapper,
                                    ArticleProjectionProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void ensureCompatible() {
        ensureCompatible(properties.getIndexName());
    }

    /** Idempotently adds identity fields and verifies every concrete target. */
    public synchronized void ensureCompatible(String indexOrAlias) {
        requireExactName(indexOrAlias);
        Request put = new Request("PUT", "/" + indexOrAlias + "/_mapping");
        put.setJsonEntity("""
                {"properties":{"revisionId":{"type":"long"},
                "contentHash":{"type":"keyword"},
                "projectionLifecycleEpoch":{"type":"long"},
                "projectionVersion":{"type":"long"},
                "projectionTombstone":{"type":"boolean"}}}
                """);
        try {
            restClient.performRequest(put);
            verifyStoredMapping(indexOrAlias);
        } catch (ResponseException exception) {
            int status = exception.getResponse().getStatusLine().getStatusCode();
            throw new IllegalStateException("article identity mapping is incompatible (status="
                    + status + ")", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("article identity mapping verification failed", exception);
        }
    }

    private void verifyStoredMapping(String indexOrAlias) throws Exception {
        Request get = new Request("GET", "/" + indexOrAlias + "/_mapping");
        JsonNode root = objectMapper.readTree(
                EntityUtils.toString(restClient.performRequest(get).getEntity()));
        Iterator<Map.Entry<String, JsonNode>> targets = root.fields();
        int count = 0;
        while (targets.hasNext()) {
            count++;
            Map.Entry<String, JsonNode> target = targets.next();
            JsonNode fields = target.getValue().path("mappings").path("properties");
            if (!"long".equals(fields.path("revisionId").path("type").asText())
                    || !"keyword".equals(fields.path("contentHash").path("type").asText())
                    || !"long".equals(fields.path("projectionLifecycleEpoch").path("type").asText())
                    || !"long".equals(fields.path("projectionVersion").path("type").asText())
                    || !"boolean".equals(fields.path("projectionTombstone").path("type").asText())) {
                throw new IllegalStateException("article identity mapping conflict on target "
                        + target.getKey());
            }
        }
        if (count == 0) {
            throw new IllegalStateException("article identity mapping resolved no concrete index");
        }
    }

    private static void requireExactName(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9._-]+")) {
            throw new IllegalArgumentException("Elasticsearch index or alias name is invalid");
        }
    }
}
