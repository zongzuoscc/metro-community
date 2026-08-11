package cumt.zongzuo.community.article.projection;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchDependencyAlignmentTest {

    @Test
    void javaAndRestClientsStayOnTheSupportedServerAxis() {
        assertThat(codeSource(ElasticsearchClient.class))
                .endsWith("/elasticsearch-java/8.18.1/elasticsearch-java-8.18.1.jar");
        assertThat(codeSource(RestClient.class))
                .endsWith("/elasticsearch-rest-client/8.18.1/elasticsearch-rest-client-8.18.1.jar");
    }

    private static String codeSource(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
    }
}
