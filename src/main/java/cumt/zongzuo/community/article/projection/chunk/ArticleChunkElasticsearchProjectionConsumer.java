package cumt.zongzuo.community.article.projection.chunk;

import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "metro.projection.article-chunk-elasticsearch",
        name = "enabled", havingValue = "true")
class ArticleChunkElasticsearchProjectionConsumer {

    private final ArticleChunkElasticsearchProjectionService service;

    ArticleChunkElasticsearchProjectionConsumer(ArticleChunkElasticsearchProjectionService service) {
        this.service = service;
    }

    @RabbitListener(id = "articleChunkElasticsearchProjectionConsumer",
            queues = RabbitConfig.ARTICLE_CHUNK_ELASTICSEARCH_QUEUE,
            containerFactory = "articleProjectionRabbitListenerContainerFactory")
    void consume(DomainEvent event) {
        if (service.apply(event) == ProjectionLease.Decision.BUSY) {
            throw new IllegalStateException("article chunk Elasticsearch projection lease is busy");
        }
    }
}
