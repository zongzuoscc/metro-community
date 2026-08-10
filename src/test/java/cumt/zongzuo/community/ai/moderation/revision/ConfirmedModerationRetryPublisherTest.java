package cumt.zongzuo.community.ai.moderation.revision;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConfirmedModerationRetryPublisherTest {

    @Test
    void brokerAckWithMandatoryReturnIsNotAcceptedAsDurableRetryDelivery() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        Message original = MessageBuilder.withBody(new byte[]{1}).build();
        doAnswer(invocation -> {
            Message sent = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(sent, 312, "NO_ROUTE", "",
                    "missing.retry.queue"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq(""), eq("article.moderation.retry.queue"),
                any(Message.class), any(CorrelationData.class));
        ConfirmedModerationRetryPublisher publisher =
                new ConfirmedModerationRetryPublisher(rabbitTemplate, Duration.ofMillis(100));

        assertThatThrownBy(() -> publisher.publish(original, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned");
        verify(rabbitTemplate).setMandatory(true);
    }
}
