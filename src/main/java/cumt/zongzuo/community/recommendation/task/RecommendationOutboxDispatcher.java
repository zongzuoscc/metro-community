package cumt.zongzuo.community.recommendation.task;

import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventOutbox;
import cumt.zongzuo.community.recommendation.mapper.RecommendationEventOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RecommendationOutboxDispatcher {

    static final String EVENT_QUEUE = "recommendation.event.queue";
    private static final int BATCH_SIZE = 100;

    private final RecommendationEventOutboxMapper mapper;
    private final EventSender sender;

    public RecommendationOutboxDispatcher(RecommendationEventOutboxMapper mapper, EventSender sender) {
        this.mapper = mapper;
        this.sender = sender;
    }

    public void dispatchPending() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        mapper.recoverStale(now.minusMinutes(5), now);
        for (RecommendationEventOutbox row : mapper.selectEligible(now, BATCH_SIZE)) {
            if (mapper.claim(row.getId(), now) != 1) {
                continue;
            }
            try {
                sender.send(row.toCommand());
                mapper.markSent(row.getId(), LocalDateTime.now().withNano(0));
            } catch (Exception exception) {
                scheduleRetry(row, exception);
            }
        }
    }

    private void scheduleRetry(RecommendationEventOutbox row, Exception exception) {
        int retryCount = row.getRetryCount() + 1;
        long delaySeconds = retryCount >= 9 ? 300 : 1L << retryCount;
        LocalDateTime failedAt = LocalDateTime.now().withNano(0);
        String error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        if (error.length() > 500) {
            error = error.substring(0, 500);
        }
        mapper.markRetry(row.getId(), retryCount, failedAt.plusSeconds(delaySeconds), error, failedAt);
        log.warn("Recommendation outbox {} delivery failed; retry {} scheduled", row.getId(), retryCount, exception);
    }

    @FunctionalInterface
    public interface EventSender {
        void send(RecommendationEventCommand command) throws Exception;
    }
}

@Component
@ConditionalOnProperty(name = "recommendation.outbox.dispatch-enabled", matchIfMissing = true)
class RecommendationOutboxSchedule {

    private final RecommendationOutboxDispatcher dispatcher;

    RecommendationOutboxSchedule(RecommendationOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${recommendation.outbox.dispatch-delay-ms:1000}")
    void dispatch() {
        dispatcher.dispatchPending();
    }
}

@Component
class CorrelatedRabbitRecommendationEventSender implements RecommendationOutboxDispatcher.EventSender {

    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    @Autowired
    CorrelatedRabbitRecommendationEventSender(RabbitTemplate rabbitTemplate) {
        this(rabbitTemplate, CONFIRM_TIMEOUT);
    }

    CorrelatedRabbitRecommendationEventSender(RabbitTemplate rabbitTemplate, Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
        this.rabbitTemplate.setMandatory(true);
    }

    @Override
    public void send(RecommendationEventCommand command) throws Exception {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.convertAndSend(RecommendationOutboxDispatcher.EVENT_QUEUE, command, correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("Rabbit returned message: " + correlation.getReturned().getReplyText());
        }
        if (!confirm.isAck()) {
            throw new IllegalStateException("Rabbit nack: " + confirm.getReason());
        }
    }
}
