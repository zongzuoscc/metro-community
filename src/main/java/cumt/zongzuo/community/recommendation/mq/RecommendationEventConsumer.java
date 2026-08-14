package cumt.zongzuo.community.recommendation.mq;

import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.entity.UserArticleEvent;
import cumt.zongzuo.community.recommendation.service.RecommendationFactPersistenceService;
import cumt.zongzuo.community.recommendation.service.RecommendationMetricsService;
import cumt.zongzuo.community.recommendation.service.RecommendationProfileService;
import cumt.zongzuo.community.recommendation.service.RecommendationProfileWriteException;
import cumt.zongzuo.community.recommendation.service.RecommendationProfileRecoveryService;
import cumt.zongzuo.community.recommendation.task.RecommendationOutboxDispatcher;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
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
    // 账号行锁必须覆盖事实写入和 Redis 画像替换。Redis 故障时仍保留事实与待修复
    // 检查点，因此只对明确的 Redis 画像写异常使用 noRollbackFor，交给恢复任务补偿。
    @Transactional(noRollbackFor = RecommendationProfileWriteException.class)
    public void consume(RecommendationEventCommand command) {
        UserArticleEvent fact = toEntity(command);
        RecommendationFactPersistenceService.PersistenceResult result = factPersistenceService.persist(fact);
        if (!result.accepted()) {
            // 已注销账号的延迟消息视为已消费，不能重试到死信队列，更不能重建画像。
            return;
        }
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
