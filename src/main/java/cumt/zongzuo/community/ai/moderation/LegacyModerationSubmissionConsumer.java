package cumt.zongzuo.community.ai.moderation;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class LegacyModerationSubmissionConsumer {

    static final String MANUAL_REASON = "AI_FOUNDATION_MANUAL_ONLY";

    private final ManualReviewRoutingService routingService;

    public LegacyModerationSubmissionConsumer(ManualReviewRoutingService routingService) {
        this.routingService = routingService;
    }

    @RabbitListener(id = "legacyModerationSubmissionConsumer", queues = "article.audit.queue")
    public void consume(Long articleId) {
        routingService.routeLegacyArticle(articleId, MANUAL_REASON);
    }
}
