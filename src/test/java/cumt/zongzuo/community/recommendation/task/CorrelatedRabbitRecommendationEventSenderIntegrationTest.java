package cumt.zongzuo.community.recommendation.task;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventOutbox;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventType;
import cumt.zongzuo.community.recommendation.mapper.RecommendationEventOutboxMapper;
import cumt.zongzuo.community.recommendation.service.RecommendationEventOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class CorrelatedRabbitRecommendationEventSenderIntegrationTest extends IntegrationTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private RecommendationEventOutboxService outboxService;
    @org.springframework.beans.factory.annotation.Autowired
    private RecommendationEventOutboxMapper outboxMapper;

    @BeforeEach
    void cleanOutbox() {
        jdbcTemplate.update("DELETE FROM recommendation_event_outbox");
    }

    @Test
    void returnedMessageLeavesOutboxPending() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doAnswer(confirming(correlation -> {
            correlation.setReturned(new ReturnedMessage(
                    new Message(new byte[0], new MessageProperties()), 312, "NO_ROUTE", "", "missing"));
        })).when(rabbitTemplate).convertAndSend(eq(RecommendationOutboxDispatcher.EVENT_QUEUE),
                any(RecommendationEventCommand.class), any(CorrelationData.class));

        dispatchAndAssertPending(rabbitTemplate, "returned");
    }

    @Test
    void nackLeavesOutboxPending() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doAnswer(confirming(correlation -> correlation.getFuture()
                .complete(new CorrelationData.Confirm(false, "broker nack"))))
                .when(rabbitTemplate).convertAndSend(eq(RecommendationOutboxDispatcher.EVENT_QUEUE),
                        any(RecommendationEventCommand.class), any(CorrelationData.class));

        dispatchAndAssertPending(rabbitTemplate, "nack");
    }

    @Test
    void confirmTimeoutLeavesOutboxPendingWithoutFiveSecondWait() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        dispatchAndAssertPending(rabbitTemplate, "timeout");
    }

    private void dispatchAndAssertPending(RabbitTemplate rabbitTemplate, String suffix) {
        RecommendationEventCommand command = command("dispatch:sender:" + suffix);
        outboxService.enqueue(command);

        new RecommendationOutboxDispatcher(outboxMapper, senderWithTimeout(rabbitTemplate)).dispatchPending();

        RecommendationEventOutbox row = outboxMapper.selectList(null).getFirst();
        assertThat(row.getStatus()).isEqualTo("PENDING");
        assertThat(row.getSentTime()).isNull();
        assertThat(row.getRetryCount()).isEqualTo(1);
    }

    private RecommendationOutboxDispatcher.EventSender senderWithTimeout(RabbitTemplate rabbitTemplate) {
        return new CorrelatedRabbitRecommendationEventSender(rabbitTemplate, Duration.ofMillis(20));
    }

    private Answer<Void> confirming(java.util.function.Consumer<CorrelationData> setup) {
        return invocation -> {
            CorrelationData correlation = invocation.getArgument(2);
            setup.accept(correlation);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        };
    }

    private RecommendationEventCommand command(String dedupeKey) {
        return new RecommendationEventCommand(7L, 21L, null, RecommendationEventType.LIKE,
                LocalDateTime.of(2026, 8, 9, 12, 0), dedupeKey, "test");
    }
}
