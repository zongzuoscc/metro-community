package cumt.zongzuo.community.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares durable work queues together with a dead-letter queue per business
 * queue. Listener retry is configured in application.yml; a message that still
 * fails after its attempts is rejected and routed to the matching DLQ instead
 * of disappearing silently.
 */
@Configuration
public class RabbitConfig {

    public static final String DEAD_LETTER_EXCHANGE = "community.dlx";
    public static final String DOMAIN_EVENT_EXCHANGE = "community.domain.events";
    public static final String ARTICLE_MODERATION_QUEUE = "article.moderation.queue";
    public static final String ARTICLE_MODERATION_RETRY_QUEUE = "article.moderation.retry.queue";
    public static final String ARTICLE_SEARCH_PROJECTION_QUEUE = "article.search.projection.queue";
    public static final String ARTICLE_MODERATION_NOTIFICATION_QUEUE = "article.moderation.notification.queue";

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE);
    }

    @Bean
    public TopicExchange domainEventExchange() {
        return new TopicExchange(DOMAIN_EVENT_EXCHANGE, true, false);
    }

    @Bean
    public Queue articleModerationQueue() {
        return workQueue(ARTICLE_MODERATION_QUEUE);
    }

    @Bean
    public Queue articleSearchProjectionQueue() {
        return workQueue(ARTICLE_SEARCH_PROJECTION_QUEUE);
    }

    @Bean
    public Queue articleModerationNotificationQueue() {
        return workQueue(ARTICLE_MODERATION_NOTIFICATION_QUEUE);
    }

    @Bean
    public Queue articleModerationDeadLetterQueue() {
        return deadLetterQueue(ARTICLE_MODERATION_QUEUE);
    }

    @Bean
    public Queue articleSearchProjectionDeadLetterQueue() {
        return deadLetterQueue(ARTICLE_SEARCH_PROJECTION_QUEUE);
    }

    @Bean
    public Queue articleModerationNotificationDeadLetterQueue() {
        return deadLetterQueue(ARTICLE_MODERATION_NOTIFICATION_QUEUE);
    }

    @Bean
    public Binding articleModerationDeadLetterBinding() {
        return deadLetterBinding(articleModerationDeadLetterQueue(), ARTICLE_MODERATION_QUEUE);
    }

    @Bean
    public Binding articleSearchProjectionDeadLetterBinding() {
        return deadLetterBinding(articleSearchProjectionDeadLetterQueue(), ARTICLE_SEARCH_PROJECTION_QUEUE);
    }

    @Bean
    public Binding articleModerationNotificationDeadLetterBinding() {
        return deadLetterBinding(articleModerationNotificationDeadLetterQueue(), ARTICLE_MODERATION_NOTIFICATION_QUEUE);
    }

    @Bean
    public Binding submittedModerationBinding() {
        return BindingBuilder.bind(articleModerationQueue()).to(domainEventExchange())
                .with("article.revision.submitted");
    }

    @Bean
    public Binding publishedSearchBinding() {
        return BindingBuilder.bind(articleSearchProjectionQueue()).to(domainEventExchange())
                .with("article.revision.published");
    }

    @Bean
    public Binding rejectedSearchBinding() {
        return BindingBuilder.bind(articleSearchProjectionQueue()).to(domainEventExchange())
                .with("article.revision.rejected");
    }

    @Bean
    public Binding supersededSearchBinding() {
        return BindingBuilder.bind(articleSearchProjectionQueue()).to(domainEventExchange())
                .with("article.revision.superseded");
    }

    @Bean
    public Binding unpublishedSearchBinding() {
        return BindingBuilder.bind(articleSearchProjectionQueue()).to(domainEventExchange())
                .with("article.unpublished");
    }

    @Bean
    public Binding deletedSearchBinding() {
        return BindingBuilder.bind(articleSearchProjectionQueue()).to(domainEventExchange())
                .with("article.deleted");
    }

    @Bean
    public Binding publishedNotificationBinding() {
        return BindingBuilder.bind(articleModerationNotificationQueue()).to(domainEventExchange())
                .with("article.revision.published");
    }

    @Bean
    public Binding rejectedNotificationBinding() {
        return BindingBuilder.bind(articleModerationNotificationQueue()).to(domainEventExchange())
                .with("article.revision.rejected");
    }

    @Bean
    public Queue mailQueue() {
        return workQueue("mail.queue");
    }

    @Bean
    public Queue notificationQueue() {
        return workQueue("message.notify.queue");
    }

    @Bean
    public Queue commentTaskQueue() {
        return workQueue("comment.task.queue");
    }

    @Bean
    public Queue likeQueue() {
        return workQueue("like.task.queue");
    }

    @Bean
    public Queue esSyncQueue() {
        return workQueue("es.sync.queue");
    }

    @Bean
    public Queue articleAuditQueue() {
        return workQueue("article.audit.queue");
    }

    @Bean
    public Queue recommendationEventQueue() {
        return workQueue("recommendation.event.queue");
    }

    @Bean
    public Queue mailDeadLetterQueue() {
        return deadLetterQueue("mail.queue");
    }

    @Bean
    public Queue notificationDeadLetterQueue() {
        return deadLetterQueue("message.notify.queue");
    }

    @Bean
    public Queue commentTaskDeadLetterQueue() {
        return deadLetterQueue("comment.task.queue");
    }

    @Bean
    public Queue likeDeadLetterQueue() {
        return deadLetterQueue("like.task.queue");
    }

    @Bean
    public Queue esSyncDeadLetterQueue() {
        return deadLetterQueue("es.sync.queue");
    }

    @Bean
    public Queue articleAuditDeadLetterQueue() {
        return deadLetterQueue("article.audit.queue");
    }

    @Bean
    public Queue recommendationEventDeadLetterQueue() {
        return deadLetterQueue("recommendation.event.queue");
    }

    @Bean
    public Binding mailDeadLetterBinding() {
        return deadLetterBinding(mailDeadLetterQueue(), "mail.queue");
    }

    @Bean
    public Binding notificationDeadLetterBinding() {
        return deadLetterBinding(notificationDeadLetterQueue(), "message.notify.queue");
    }

    @Bean
    public Binding commentTaskDeadLetterBinding() {
        return deadLetterBinding(commentTaskDeadLetterQueue(), "comment.task.queue");
    }

    @Bean
    public Binding likeDeadLetterBinding() {
        return deadLetterBinding(likeDeadLetterQueue(), "like.task.queue");
    }

    @Bean
    public Binding esSyncDeadLetterBinding() {
        return deadLetterBinding(esSyncDeadLetterQueue(), "es.sync.queue");
    }

    @Bean
    public Binding articleAuditDeadLetterBinding() {
        return deadLetterBinding(articleAuditDeadLetterQueue(), "article.audit.queue");
    }

    @Bean
    public Binding recommendationEventDeadLetterBinding() {
        return deadLetterBinding(recommendationEventDeadLetterQueue(), "recommendation.event.queue");
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter(
                "cumt.zongzuo.community.dto",
                "cumt.zongzuo.community.recommendation.dto",
                "cumt.zongzuo.community.event.domain");
    }

    private Queue workQueue(String name) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(name + ".dlq")
                .build();
    }

    private Queue deadLetterQueue(String originalQueueName) {
        return QueueBuilder.durable(originalQueueName + ".dlq").build();
    }

    private Binding deadLetterBinding(Queue queue, String originalQueueName) {
        return BindingBuilder.bind(queue)
                .to(deadLetterExchange())
                .with(originalQueueName + ".dlq");
    }
}
