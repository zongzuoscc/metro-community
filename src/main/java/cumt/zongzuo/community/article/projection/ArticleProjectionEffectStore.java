package cumt.zongzuo.community.article.projection;

import com.fasterxml.jackson.databind.node.ObjectNode;
import cumt.zongzuo.community.document.ArticleDoc;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Applies current-pointer state with a durable, per-document monotonic fence.
 * Tombstones remain in the same document so the fence survives ES delete GC.
 */
@Component
class ArticleProjectionEffectStore {

    private static final DateTimeFormatter ES_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String MONOTONIC_REPLACE = """
            def oldEpoch = ctx._source['projectionLifecycleEpoch'];
            def oldVersion = ctx._source['projectionVersion'];
            if (oldEpoch == null || oldVersion == null
                || params.lifecycleEpoch > oldEpoch
                || (params.lifecycleEpoch == oldEpoch && params.projectionVersion >= oldVersion)) {
              ctx._source.clear();
              ctx._source.putAll(params.document);
            } else {
              ctx.op = 'noop';
            }
            """;

    private final RestClient restClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ArticleProjectionProperties properties;

    ArticleProjectionEffectStore(RestClient restClient,
                                 com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                 ArticleProjectionProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    void apply(long articleId, ArticleProjectionSource.Snapshot snapshot) {
        if (articleId <= 0 || snapshot == null) {
            throw new IllegalArgumentException("article projection effect requires a valid snapshot");
        }
        if (snapshot.document() != null && !Long.valueOf(articleId).equals(snapshot.document().getId())) {
            throw new IllegalArgumentException("article projection document id does not match aggregate");
        }

        ObjectNode replacement = snapshot.present()
                ? liveDocument(snapshot) : tombstoneDocument(articleId, snapshot);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("scripted_upsert", true);
        ObjectNode script = body.putObject("script");
        script.put("lang", "painless");
        script.put("source", MONOTONIC_REPLACE);
        ObjectNode parameters = script.putObject("params");
        parameters.put("lifecycleEpoch", snapshot.lifecycleEpoch());
        parameters.put("projectionVersion", snapshot.projectionVersion());
        parameters.set("document", replacement);
        body.set("upsert", objectMapper.createObjectNode());

        Request request = new Request("POST", "/" + properties.getIndexName()
                + "/_update/" + articleId);
        request.addParameter("retry_on_conflict", "10");
        request.setJsonEntity(body.toString());
        try {
            restClient.performRequest(request);
        } catch (Exception exception) {
            throw new IllegalStateException("monotonic article projection effect failed", exception);
        }
    }

    private ObjectNode liveDocument(ArticleProjectionSource.Snapshot snapshot) {
        ArticleDoc document = snapshot.document();
        ObjectNode value = baseDocument(document.getId(), snapshot, false);
        putNullable(value, "revisionId", document.getRevisionId());
        putNullable(value, "contentHash", document.getContentHash());
        putNullable(value, "title", document.getTitle());
        putNullable(value, "content", document.getContent());
        putNullable(value, "summary", document.getSummary());
        putNullable(value, "cover", document.getCover());
        putNullable(value, "authorId", document.getAuthorId());
        putNullable(value, "viewCount", document.getViewCount());
        putNullable(value, "likeCount", document.getLikeCount());
        putNullable(value, "commentCount", document.getCommentCount());
        putNullable(value, "collectCount", document.getCollectCount());
        if (document.getCreateTime() == null) {
            value.putNull("createTime");
        } else {
            value.put("createTime", ES_DATE.format(document.getCreateTime()));
        }
        return value;
    }

    private ObjectNode tombstoneDocument(long articleId, ArticleProjectionSource.Snapshot snapshot) {
        return baseDocument(articleId, snapshot, true);
    }

    private ObjectNode baseDocument(long articleId, ArticleProjectionSource.Snapshot snapshot,
                                    boolean tombstone) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("id", articleId);
        value.put("projectionLifecycleEpoch", snapshot.lifecycleEpoch());
        value.put("projectionVersion", snapshot.projectionVersion());
        value.put("projectionTombstone", tombstone);
        return value;
    }

    private static void putNullable(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putNullable(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}
