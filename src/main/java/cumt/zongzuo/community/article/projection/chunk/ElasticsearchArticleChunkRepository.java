package cumt.zongzuo.community.article.projection.chunk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
@ConditionalOnProperty(prefix = "metro.projection.article-chunk-elasticsearch",
        name = "enabled", havingValue = "true")
class ElasticsearchArticleChunkRepository implements ArticleChunkSearchRepository {

    private static final DateTimeFormatter ES_DATE = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String REPLACE_IF_CURRENT = """
            def oldEpoch=ctx._source['projectionLifecycleEpoch'];
            def oldVersion=ctx._source['projectionVersion'];
            if (oldEpoch==null || oldVersion==null || params.lifecycleEpoch>oldEpoch
                || (params.lifecycleEpoch==oldEpoch && params.projectionVersion>=oldVersion)) {
              ctx._source.clear(); ctx._source.putAll(params.document);
            } else { ctx.op='noop'; }
            """;
    private static final String DEACTIVATE_IF_CURRENT = """
            def oldEpoch=ctx._source['projectionLifecycleEpoch'];
            def oldVersion=ctx._source['projectionVersion'];
            if (oldEpoch==null || oldVersion==null || params.lifecycleEpoch>oldEpoch
                || (params.lifecycleEpoch==oldEpoch && params.projectionVersion>=oldVersion)) {
              ctx._source['active']=false;
              ctx._source['projectionLifecycleEpoch']=params.lifecycleEpoch;
              ctx._source['projectionVersion']=params.projectionVersion;
            } else { ctx.op='noop'; }
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String indexName;
    private volatile boolean mappingReady;

    ElasticsearchArticleChunkRepository(RestClient restClient,
                                        ObjectMapper objectMapper,
                                        @Value("${metro.projection.article-chunk-elasticsearch.index-name:article-chunks-v1}")
                                        String indexName) {
        if (indexName == null || !indexName.matches("[a-z0-9._-]+")) {
            throw new IllegalArgumentException("article chunk Elasticsearch index name is invalid");
        }
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.indexName = indexName;
    }

    synchronized void ensureCompatible() {
        if (mappingReady) {
            return;
        }
        try {
            Response head = restClient.performRequest(new Request("HEAD", "/" + indexName));
            int status = head.getStatusLine().getStatusCode();
            if (status == 404) {
                Request create = new Request("PUT", "/" + indexName);
                create.setJsonEntity("""
                        {"settings":{"number_of_shards":1,"number_of_replicas":0},
                         "mappings":{"dynamic":"strict","properties":{
                          "chunkId":{"type":"long"},"articleId":{"type":"long"},
                          "revisionId":{"type":"long"},"chunkNo":{"type":"integer"},
                          "parserGeneration":{"type":"long"},"parserVersion":{"type":"keyword"},
                          "title":{"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},
                          "headingPath":{"type":"keyword"},
                          "bodyText":{"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},
                          "estimatedTokens":{"type":"integer"},"revisionContentHash":{"type":"keyword"},
                          "chunkHash":{"type":"keyword"},"embeddingInputHash":{"type":"keyword"},
                          "language":{"type":"keyword"},"publishedAt":{"type":"date"},
                          "active":{"type":"boolean"},"projectionLifecycleEpoch":{"type":"long"},
                          "projectionVersion":{"type":"long"},"sourceAggregateVersion":{"type":"long"}
                        }}}
                        """);
                restClient.performRequest(create);
            } else if (status != 200) {
                throw new IllegalStateException("article chunk Elasticsearch index HEAD returned " + status);
            }
            JsonNode mapping = response(new Request("GET", "/" + indexName + "/_mapping"));
            JsonNode properties = mapping.path(indexName).path("mappings").path("properties");
            if (!"text".equals(properties.path("bodyText").path("type").asText())
                    || !"ik_max_word".equals(properties.path("bodyText").path("analyzer").asText())
                    || !"long".equals(properties.path("projectionVersion").path("type").asText())
                    || !"boolean".equals(properties.path("active").path("type").asText())) {
                throw new IllegalStateException("article chunk Elasticsearch mapping is incompatible");
            }
            mappingReady = true;
        } catch (Exception exception) {
            throw new IllegalStateException("article chunk Elasticsearch schema is unavailable", exception);
        }
    }

    void replace(ArticleChunkSearchSource.Snapshot snapshot) {
        ensureCompatible();
        deactivate(snapshot.articleId(), snapshot.lifecycleEpoch(), snapshot.chunkSetVersion(), null);
        for (ArticleChunkSearchSource.Chunk chunk : snapshot.chunks()) {
            upsert(snapshot, chunk);
        }
        refresh();
    }

    void compensate(ArticleChunkSearchSource.Snapshot snapshot) {
        deactivate(snapshot.articleId(), snapshot.lifecycleEpoch(), snapshot.chunkSetVersion(), true);
        refresh();
    }

    @Override
    public java.util.List<ArticleChunkSearchHit> searchActive(String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("article chunk search query must not be blank");
        }
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("article chunk search topK must be between 1 and 100");
        }
        ensureCompatible();
        ObjectNode body = objectMapper.createObjectNode().put("size", topK);
        ObjectNode bool = body.putObject("query").putObject("bool");
        bool.putArray("filter").addObject().putObject("term").put("active", true);
        ObjectNode multiMatch = bool.putObject("must").putObject("multi_match");
        multiMatch.put("query", query).put("type", "best_fields");
        multiMatch.putArray("fields").add("title^3").add("headingPath^2").add("bodyText");
        body.putArray("_source").add("chunkId").add("articleId").add("revisionId");
        Request request = new Request("POST", "/" + indexName + "/_search");
        request.setJsonEntity(body.toString());
        try {
            JsonNode hits = response(request).path("hits").path("hits");
            java.util.ArrayList<ArticleChunkSearchHit> result = new java.util.ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                result.add(new ArticleChunkSearchHit(source.path("chunkId").asLong(),
                        source.path("articleId").asLong(), source.path("revisionId").asLong(),
                        (float) hit.path("_score").asDouble()));
            }
            return java.util.List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalStateException("search active article chunks failed", exception);
        }
    }

    private void deactivate(long articleId, long epoch, long version, Boolean exactTuple) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode bool = body.putObject("query").putObject("bool");
        var filters = bool.putArray("filter");
        filters.addObject().putObject("term").put("articleId", articleId);
        if (Boolean.TRUE.equals(exactTuple)) {
            filters.addObject().putObject("term").put("projectionLifecycleEpoch", epoch);
            filters.addObject().putObject("term").put("projectionVersion", version);
        }
        ObjectNode script = body.putObject("script");
        script.put("lang", "painless");
        script.put("source", Boolean.TRUE.equals(exactTuple)
                ? "ctx._source['active']=false" : DEACTIVATE_IF_CURRENT);
        if (!Boolean.TRUE.equals(exactTuple)) {
            script.putObject("params").put("lifecycleEpoch", epoch).put("projectionVersion", version);
        }
        Request request = new Request("POST", "/" + indexName + "/_update_by_query");
        request.addParameter("conflicts", "proceed");
        request.setJsonEntity(body.toString());
        perform(request, "deactivate article chunks");
    }

    private void upsert(ArticleChunkSearchSource.Snapshot snapshot,
                        ArticleChunkSearchSource.Chunk chunk) {
        ObjectNode document = objectMapper.createObjectNode();
        document.put("chunkId", chunk.id()).put("articleId", snapshot.articleId())
                .put("revisionId", chunk.revisionId()).put("chunkNo", chunk.chunkNo())
                .put("parserGeneration", chunk.parserGeneration())
                .put("parserVersion", chunk.parserVersion()).put("title", chunk.title())
                .set("headingPath", chunk.headingPath());
        document.put("bodyText", chunk.bodyText()).put("estimatedTokens", chunk.estimatedTokens())
                .put("revisionContentHash", chunk.revisionContentHash())
                .put("chunkHash", chunk.chunkHash()).put("embeddingInputHash", chunk.embeddingInputHash())
                .put("language", chunk.language()).put("publishedAt", ES_DATE.format(chunk.publishedAt()))
                .put("active", true).put("projectionLifecycleEpoch", snapshot.lifecycleEpoch())
                .put("projectionVersion", snapshot.chunkSetVersion())
                .put("sourceAggregateVersion", snapshot.sourceAggregateVersion());
        ObjectNode body = objectMapper.createObjectNode().put("scripted_upsert", true);
        ObjectNode script = body.putObject("script");
        script.put("lang", "painless").put("source", REPLACE_IF_CURRENT);
        script.putObject("params").put("lifecycleEpoch", snapshot.lifecycleEpoch())
                .put("projectionVersion", snapshot.chunkSetVersion()).set("document", document);
        body.set("upsert", objectMapper.createObjectNode());
        Request request = new Request("POST", "/" + indexName + "/_update/" + chunk.id());
        request.addParameter("retry_on_conflict", "10");
        request.setJsonEntity(body.toString());
        perform(request, "upsert article chunk");
    }

    private void refresh() {
        perform(new Request("POST", "/" + indexName + "/_refresh"), "refresh article chunks");
    }

    private JsonNode response(Request request) throws Exception {
        return objectMapper.readTree(EntityUtils.toString(restClient.performRequest(request).getEntity()));
    }

    private void perform(Request request, String operation) {
        try {
            restClient.performRequest(request);
        } catch (Exception exception) {
            throw new IllegalStateException(operation + " failed", exception);
        }
    }
}
