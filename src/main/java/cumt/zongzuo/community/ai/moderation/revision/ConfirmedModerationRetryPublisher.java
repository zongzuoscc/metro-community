package cumt.zongzuo.community.ai.moderation.revision;

import cumt.zongzuo.community.config.RabbitConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Mandatory, confirmed publisher for BUSY deliveries that must survive the active lease. */
final class ConfirmedModerationRetryPublisher {

    private static final Duration DEFAULT_CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    ConfirmedModerationRetryPublisher(ConnectionFactory connectionFactory,
                                      MessageConverter messageConverter) {
        this(new RabbitTemplate(connectionFactory), DEFAULT_CONFIRM_TIMEOUT);
        this.rabbitTemplate.setMessageConverter(messageConverter);
    }

    ConfirmedModerationRetryPublisher(RabbitTemplate rabbitTemplate, Duration confirmTimeout) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
        this.confirmTimeout = Objects.requireNonNull(confirmTimeout, "confirmTimeout");
        if (confirmTimeout.isZero() || confirmTimeout.isNegative()) {
            throw new IllegalArgumentException("confirmTimeout must be positive");
        }
        this.rabbitTemplate.setMandatory(true);
    }

    void publish(Message original, Duration retryDelay) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isZero() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must be positive");
        }
        Message delayed = MessageBuilder.fromMessage(original)
                .setExpiration(Long.toString(retryDelay.toMillis()))
                .build();
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.send("", RabbitConfig.ARTICLE_MODERATION_RETRY_QUEUE,
                delayed, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("Rabbit returned unroutable moderation retry");
            }
            if (!confirm.isAck()) {
                throw new IllegalStateException("Rabbit nack for moderation retry");
            }
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Moderation retry publish interrupted", interrupted);
        }
        catch (ExecutionException | TimeoutException publishFailure) {
            throw new IllegalStateException("Moderation retry publish was not confirmed",
                    publishFailure);
        }
    }
}
