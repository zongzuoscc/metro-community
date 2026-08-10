package cumt.zongzuo.community.ai.moderation.revision;

import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.event.domain.DomainEvent;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Durable entry point for revision-bound shadow moderation events. */
@Component
public class ArticleModerationEventConsumer {

    private final ArticleModerationWorker worker;

    public ArticleModerationEventConsumer(ArticleModerationWorker worker) {
        this.worker = worker;
    }

    @RabbitListener(id = "articleModerationEventConsumer",
            queues = RabbitConfig.ARTICLE_MODERATION_QUEUE,
            containerFactory = "articleModerationRabbitListenerContainerFactory")
    public void consume(DomainEvent event) {
        ArticleModerationWorker.ProcessOutcome outcome = worker.process(event);
        if (outcome == ArticleModerationWorker.ProcessOutcome.BUSY) {
            throw new ModerationBusyException("moderation job lease is still active");
        }
        if (outcome == ArticleModerationWorker.ProcessOutcome.DEFERRED) {
            throw new ModerationBusyException("moderation processing is fenced");
        }
    }

    static final class ModerationBusyException extends IllegalStateException {
        ModerationBusyException(String message) {
            super(message);
        }
    }
}
