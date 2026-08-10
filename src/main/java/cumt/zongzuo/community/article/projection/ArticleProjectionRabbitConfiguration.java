package cumt.zongzuo.community.article.projection;

import org.aopalliance.aop.Advice;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ArticleProjectionProperties.class)
class ArticleProjectionRabbitConfiguration {

    @Bean("articleProjectionRabbitListenerContainerFactory")
    SimpleRabbitListenerContainerFactory articleProjectionRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            ArticleProjectionProperties properties) {
        properties.validate();
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        ArticleProjectionProperties.Retry retry = properties.getRetry();
        Advice retryAdvice = RetryInterceptorBuilder.stateless()
                .maxAttempts(retry.getMaxAttempts())
                .backOffOptions(retry.getInitialInterval().toMillis(), retry.getMultiplier(),
                        retry.getMaxInterval().toMillis())
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
        factory.setAdviceChain(retryAdvice);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        return factory;
    }
}
