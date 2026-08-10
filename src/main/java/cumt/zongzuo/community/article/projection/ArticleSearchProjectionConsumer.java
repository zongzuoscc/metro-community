package cumt.zongzuo.community.article.projection;

import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ArticleSearchProjectionConsumer {

    static final String CONSUMER = ArticleSearchProjectionService.CONSUMER;
    private final ArticleSearchProjectionService projectionService;

    ArticleSearchProjectionConsumer(ArticleSearchProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @RabbitListener(id = "articleSearchProjectionConsumer",
            queues = RabbitConfig.ARTICLE_SEARCH_PROJECTION_QUEUE,
            containerFactory = "articleProjectionRabbitListenerContainerFactory")
    public void consume(DomainEvent event) {
        ArticleSearchProjectionService.ApplyResult result = projectionService.apply(event);
        if (result.decision() == ProjectionLease.Decision.BUSY) {
            throw new ProjectionBusyException("article projection aggregate lease is busy");
        }
    }

    static final class ProjectionBusyException extends IllegalStateException {
        ProjectionBusyException(String message) {
            super(message);
        }
    }
}
