package cumt.zongzuo.community.recommendation.mq;

import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.entity.UserArticleEvent;
import cumt.zongzuo.community.recommendation.mapper.UserArticleEventMapper;
import cumt.zongzuo.community.recommendation.service.RecommendationMetricsService;
import cumt.zongzuo.community.recommendation.service.RecommendationProfileService;
import cumt.zongzuo.community.recommendation.task.RecommendationOutboxDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
public class RecommendationEventConsumer {

    private final UserArticleEventMapper eventMapper;
    private final RecommendationProfileService profileService;
    private final RecommendationMetricsService metricsService;

    public RecommendationEventConsumer(UserArticleEventMapper eventMapper,
                                       RecommendationProfileService profileService,
                                       RecommendationMetricsService metricsService) {
        this.eventMapper = eventMapper;
        this.profileService = profileService;
        this.metricsService = metricsService;
    }

    @RabbitListener(id = "recommendationEventConsumer", queues = RecommendationOutboxDispatcher.EVENT_QUEUE)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void consume(RecommendationEventCommand command) {
        boolean inserted = false;
        try {
            eventMapper.insert(toEntity(command));
            inserted = true;
        } catch (DuplicateKeyException duplicate) {
            log.debug("Recommendation fact already exists: {}", command.dedupeKey());
        }
        if (inserted) {
            metricsService.recordEvent(command);
        }
        profileService.rebuildProfile(command.userId());
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
