package cumt.zongzuo.community.ai.agent.memory.index;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.Duration;

/** 复用现有连接配置，不复用会被 SDK 改写超时的文章客户端，也不依赖旧 semantic-enabled 开关。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"metro.ai.enabled", "metro.ai.embedding.enabled", "metro.ai.memory.enabled"}, havingValue = "true")
public class AgentMemoryVectorConfiguration {
    @Bean(destroyMethod = "close")
    MilvusClientV2 agentMemoryMilvusClient(
            @Value("${metro.projection.article-chunk-milvus.uri}") String uri,
            @Value("${metro.projection.article-chunk-milvus.username}") String username,
            @Value("${metro.projection.article-chunk-milvus.password}") String password,
            @Value("${metro.projection.article-chunk-milvus.connect-timeout:PT10S}") Duration connectTimeout,
            @Value("${metro.projection.article-chunk-milvus.rpc-timeout:PT60S}") Duration rpcTimeout) {
        if (uri.isBlank() || username.isBlank() || password.isBlank())
            throw new IllegalStateException("Memory Milvus connection configuration is incomplete");
        return new MilvusClientV2(ConnectConfig.builder().uri(uri).username(username).password(password)
                .connectTimeoutMs(connectTimeout.toMillis()).rpcDeadlineMs(rpcTimeout.toMillis()).build());
    }

    @Bean
    MemoryVectorRepository memoryVectorRepository(
            @Qualifier("agentMemoryMilvusClient") MilvusClientV2 client, Clock clock,
            @Value("${metro.ai.memory.milvus.collection:metro_user_memories_v1}") String collection,
            @Value("${metro.ai.memory.milvus.initialize-schema:false}") boolean initializeSchema,
            @Value("${metro.ai.memory.milvus.minimum-score:0.35}") double minimumScore) {
        return new SdkMemoryVectorRepository(client, collection, initializeSchema, clock, minimumScore);
    }
}
