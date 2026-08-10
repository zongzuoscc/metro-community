package cumt.zongzuo.community.ai.moderation.revision;

import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class ArticleModerationRabbitConfiguration {

    @Bean
    Queue articleModerationRetryQueue() {
        return QueueBuilder.durable(RabbitConfig.ARTICLE_MODERATION_RETRY_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(RabbitConfig.ARTICLE_MODERATION_QUEUE)
                .build();
    }

    @Bean
    ConfirmedModerationRetryPublisher confirmedModerationRetryPublisher(
            ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        return new ConfirmedModerationRetryPublisher(connectionFactory, messageConverter);
    }

    @Bean("articleModerationRabbitListenerContainerFactory")
    SimpleRabbitListenerContainerFactory articleModerationRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            ConfirmedModerationRetryPublisher retryPublisher,
            MetroAiProperties properties,
            RabbitProperties rabbitProperties,
            ArticleRevisionModeResolver modeResolver) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        RejectAndDontRequeueRecoverer poisonRecoverer = new RejectAndDontRequeueRecoverer();
        MessageRecoverer recoverer = (message, cause) -> {
            if (containsBusy(cause)) {
                Duration retryDelay = properties.getModeration().getLeaseDuration()
                        .plusSeconds(1);
                retryPublisher.publish(message, retryDelay);
            }
            else {
                poisonRecoverer.recover(message, cause);
            }
        };
        Advice retry = RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1_000L, 2.0, 2_000L)
                .recoverer(recoverer)
                .build();
        factory.setAdviceChain(retry);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        ArticleRevisionMode mode = modeResolver.current();
        factory.setAutoStartup(rabbitProperties.getListener().getSimple().isAutoStartup()
                && mode != ArticleRevisionMode.VERIFY_FENCE
                && mode != ArticleRevisionMode.POINTER_READ);
        return factory;
    }

    private static boolean containsBusy(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ArticleModerationEventConsumer.ModerationBusyException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
