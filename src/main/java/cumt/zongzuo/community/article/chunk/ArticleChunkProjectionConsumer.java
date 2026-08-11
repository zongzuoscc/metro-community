package cumt.zongzuo.community.article.chunk;

import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "metro.projection.article-chunks", name = "enabled", havingValue = "true")
public class ArticleChunkProjectionConsumer {

    private final ArticleChunkProjectionService service;

    public ArticleChunkProjectionConsumer(ArticleChunkProjectionService service) {
        this.service = service;
    }

    @RabbitListener(id = "articleChunkProjectionConsumer",
            queues = RabbitConfig.ARTICLE_CHUNK_FACT_QUEUE,
            containerFactory = "articleProjectionRabbitListenerContainerFactory")
    public void consume(DomainEvent event) {
        if (service.apply(event).decision() == ProjectionLease.Decision.BUSY) {
            throw new IllegalStateException("article chunk projection aggregate lease is busy");
        }
    }
}
