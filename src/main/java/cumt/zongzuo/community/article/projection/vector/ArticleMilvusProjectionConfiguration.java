package cumt.zongzuo.community.article.projection.vector;

import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.event.projection.ProjectionLeaseService;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {
        "metro.ai.enabled",
        "metro.ai.embedding.enabled",
        "metro.projection.article-chunk-milvus.enabled"
}, havingValue = "true")
class ArticleMilvusProjectionConfiguration {

    @Bean(destroyMethod = "close")
    MilvusClientV2 articleProjectionMilvusClient(
            @Value("${metro.projection.article-chunk-milvus.uri}") String uri,
            @Value("${metro.projection.article-chunk-milvus.username}") String username,
            @Value("${metro.projection.article-chunk-milvus.password}") String password,
            @Value("${metro.projection.article-chunk-milvus.connect-timeout:PT10S}") Duration connectTimeout,
            @Value("${metro.projection.article-chunk-milvus.rpc-timeout:PT60S}") Duration rpcTimeout) {
        requireText(uri, "Milvus URI");
        requireText(username, "Milvus username");
        requireText(password, "Milvus password");
        return new MilvusClientV2(ConnectConfig.builder()
                .uri(uri)
                .username(username)
                .password(password)
                .connectTimeoutMs(connectTimeout.toMillis())
                .rpcDeadlineMs(rpcTimeout.toMillis())
                .build());
    }

    @Bean
    ArticleVectorRepository articleVectorRepository(MilvusClientV2 articleProjectionMilvusClient) {
        return new SdkArticleVectorRepository(articleProjectionMilvusClient);
    }

    @Bean
    ArticleMilvusProjectionService articleMilvusProjectionService(
            ProjectionLeaseService leases,
            ArticleVectorProjectionSource source,
            ArticleVectorRepository repository,
            AiCapabilityExecutor executor,
            EmbeddingGateway embeddingGateway,
            MetroAiProperties aiProperties,
            Clock clock,
            @Value("${metro.projection.article-chunk-milvus.physical-collection:metro_article_chunks_bgem3_v1}")
            String physicalCollection,
            @Value("${metro.projection.article-chunk-milvus.lease-duration:PT60S}")
            Duration leaseDuration) {
        return new ArticleMilvusProjectionService(leases, source, repository, executor,
                embeddingGateway, clock, physicalCollection, aiProperties.getOllama().getModel(),
                leaseDuration, aiProperties.getEmbedding().getTimeout());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " must not be blank when Milvus projection is enabled");
        }
    }
}
