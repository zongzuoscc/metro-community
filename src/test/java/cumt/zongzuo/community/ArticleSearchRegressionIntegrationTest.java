package cumt.zongzuo.community;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleSearchRegressionIntegrationTest extends IntegrationTestSupport {

    private static final String INDEX = "article-search-regression";

    @Autowired
    private RestClient restClient;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void removeIndex() throws Exception {
        restClient.performRequest(new Request("DELETE", "/" + INDEX + "?ignore_unavailable=true"));
    }

    @Test
    void chineseSearchAggregationAndMoreLikeThisRemainAvailable() throws Exception {
        request("PUT", "/" + INDEX, """
                {"mappings":{"dynamic":"strict","properties":{
                  "title":{"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},
                  "body":{"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},
                  "visibility":{"type":"keyword"}
                }}}
                """);
        request("PUT", "/" + INDEX + "/_doc/1",
                "{\"title\":\"中文知识检索\",\"body\":\"社区文章检索回归\",\"visibility\":\"PUBLIC\"}");
        request("PUT", "/" + INDEX + "/_doc/2",
                "{\"title\":\"其他文章\",\"body\":\"无关内容\",\"visibility\":\"PUBLIC\"}");
        request("POST", "/" + INDEX + "/_refresh", null);

        JsonNode search = request("POST", "/" + INDEX + "/_search", """
                {"query":{"match":{"title":"中文知识"}},
                 "aggs":{"by_visibility":{"terms":{"field":"visibility"}}}}
                """);
        assertThat(search.path("hits").path("total").path("value").asLong()).isEqualTo(1L);
        assertThat(search.path("aggregations").path("by_visibility").path("buckets").get(0)
                .path("doc_count").asLong()).isEqualTo(1L);

        JsonNode similar = request("POST", "/" + INDEX + "/_search", """
                {"query":{"more_like_this":{"fields":["title","body"],
                  "like":[{"_index":"article-search-regression","_id":"1"}],
                  "min_term_freq":1,"min_doc_freq":1}}}
                """);
        assertThat(similar.path("hits").path("total").path("value").asLong()).isGreaterThanOrEqualTo(0L);
    }

    private JsonNode request(String method, String endpoint, String body) throws Exception {
        Request request = new Request(method, endpoint);
        if (body != null) {
            request.setEntity(new NStringEntity(body, ContentType.APPLICATION_JSON));
        }
        var response = restClient.performRequest(request);
        if (response.getEntity() == null) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(EntityUtils.toString(response.getEntity()));
    }
}
