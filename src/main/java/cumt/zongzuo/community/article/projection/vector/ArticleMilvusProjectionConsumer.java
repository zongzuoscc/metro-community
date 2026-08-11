package cumt.zongzuo.community.article.projection.vector;

import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {
        "metro.ai.enabled",
        "metro.ai.embedding.enabled",
        "metro.projection.article-chunk-milvus.enabled"
}, havingValue = "true")
class ArticleMilvusProjectionConsumer {

    private final ArticleMilvusProjectionService service;

    ArticleMilvusProjectionConsumer(ArticleMilvusProjectionService service) {
        this.service = service;
    }

    @RabbitListener(id = "articleChunkMilvusProjectionConsumer",
            queues = RabbitConfig.ARTICLE_CHUNK_MILVUS_QUEUE,
            containerFactory = "articleProjectionRabbitListenerContainerFactory")
    void consume(DomainEvent event) {
        if (service.apply(event) == ProjectionLease.Decision.BUSY) {
            throw new IllegalStateException("article Milvus projection lease is busy");
        }
    }
}
