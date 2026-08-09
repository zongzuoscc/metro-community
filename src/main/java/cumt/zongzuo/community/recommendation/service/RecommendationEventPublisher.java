package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RecommendationEventPublisher {

    public static final String QUEUE = "recommendation.event.queue";

    private final RabbitTemplate rabbitTemplate;

    public RecommendationEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishAfterCommit(RecommendationEventCommand command) {
        Runnable send = () -> rabbitTemplate.convertAndSend(QUEUE, command);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send.run();
            }
        });
    }
}
