package cumt.zongzuo.community.event.outbox;

import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.event.domain.DomainEvent;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@FunctionalInterface
public interface DomainEventPublisher {
    void publish(DomainEvent event, String leaseOwner) throws Exception;
}

/** Dedicated mandatory/correlated template so unrelated senders cannot weaken confirms. */
@Component
class CorrelatedRabbitDomainEventPublisher implements DomainEventPublisher {

    private static final Duration DEFAULT_CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    @Autowired
    CorrelatedRabbitDomainEventPublisher(ConnectionFactory connectionFactory,
                                         MessageConverter messageConverter) {
        this(connectionFactory, messageConverter, DEFAULT_CONFIRM_TIMEOUT);
    }

    CorrelatedRabbitDomainEventPublisher(ConnectionFactory connectionFactory,
                                         MessageConverter messageConverter,
                                         Duration confirmTimeout) {
        this.rabbitTemplate = new RabbitTemplate(connectionFactory);
        this.rabbitTemplate.setMessageConverter(messageConverter);
        this.rabbitTemplate.setMandatory(true);
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void publish(DomainEvent event, String leaseOwner) throws Exception {
        Objects.requireNonNull(event, "event");
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
        CorrelationData correlation = new CorrelationData(event.eventId() + ":" + leaseOwner);
        rabbitTemplate.convertAndSend(RabbitConfig.DOMAIN_EVENT_EXCHANGE,
                event.eventType().routingKey(), event, correlation);
        CorrelationData.Confirm confirm = correlation.getFuture()
                .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("Rabbit returned unroutable domain event");
        }
        if (!confirm.isAck()) {
            throw new IllegalStateException("Rabbit nack for domain event: "
                    + safeReason(confirm.getReason()));
        }
    }

    private static String safeReason(String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason.replaceAll("[\\p{Cntrl}]", " ");
    }
}
