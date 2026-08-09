package cumt.zongzuo.community.recommendation.mq;

import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.entity.UserArticleEvent;
import cumt.zongzuo.community.recommendation.service.RecommendationFactPersistenceService;
import cumt.zongzuo.community.recommendation.service.RecommendationMetricsService;
import cumt.zongzuo.community.recommendation.service.RecommendationProfileService;
import cumt.zongzuo.community.recommendation.service.RecommendationProfileRecoveryService;
import cumt.zongzuo.community.recommendation.task.RecommendationOutboxDispatcher;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class RecommendationEventConsumer {

    private final RecommendationFactPersistenceService factPersistenceService;
    private final RecommendationProfileService profileService;
    private final RecommendationMetricsService metricsService;
    private final RecommendationProfileRecoveryService profileRecoveryService;

    public RecommendationEventConsumer(RecommendationFactPersistenceService factPersistenceService,
                                       RecommendationProfileService profileService,
                                       RecommendationMetricsService metricsService,
                                       RecommendationProfileRecoveryService profileRecoveryService) {
        this.factPersistenceService = factPersistenceService;
        this.profileService = profileService;
        this.metricsService = metricsService;
        this.profileRecoveryService = profileRecoveryService;
    }

    @RabbitListener(id = "recommendationEventConsumer", queues = RecommendationOutboxDispatcher.EVENT_QUEUE)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void consume(RecommendationEventCommand command) {
        UserArticleEvent fact = toEntity(command);
        RecommendationFactPersistenceService.PersistenceResult result = factPersistenceService.persist(fact);
        if (result.inserted()) {
            metricsService.recordEvent(command);
        }
        profileService.rebuildProfile(command.userId());
        profileRecoveryService.markRebuilt(command.userId(), result.factId());
    }

    private UserArticleEvent toEntity(RecommendationEventCommand command) {
        UserArticleEvent event = new UserArticleEvent();
        event.setUserId(command.userId());
        event.setArticleId(command.articleId());
        event.setTargetAuthorId(command.targetAuthorId());
        event.setEventType(command.eventType().name());
        event.setOccurredAt(command.occurredAt());
        event.setDedupeKey(command.dedupeKey());
        event.setSource(command.source());
        event.setCreateTime(LocalDateTime.now().withNano(0));
        return event;
    }
}
