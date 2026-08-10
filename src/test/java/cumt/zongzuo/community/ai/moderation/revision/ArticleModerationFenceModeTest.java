package cumt.zongzuo.community.ai.moderation.revision;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.provider.AiChatGateway;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.utils.SensitiveUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.beans.DirectFieldAccessor;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArticleModerationFenceModeTest {

    @ParameterizedTest
    @EnumSource(value = ArticleRevisionMode.class, names = {"VERIFY_FENCE", "POINTER_READ"})
    void defensiveConsumerInvocationNeverAcknowledgesADeferredEvent(ArticleRevisionMode mode) {
        ArticleModerationWorker worker = mock(ArticleModerationWorker.class);
        DomainEvent event = event();
        when(worker.process(event)).thenReturn(ArticleModerationWorker.ProcessOutcome.DEFERRED);

        assertThatThrownBy(() -> new ArticleModerationEventConsumer(worker).consume(event))
                .isInstanceOf(ArticleModerationEventConsumer.ModerationBusyException.class)
                .hasMessageContaining("fenced");
    }

    @ParameterizedTest
    @EnumSource(value = ArticleRevisionMode.class, names = {"VERIFY_FENCE", "POINTER_READ"})
    void workerDefersBeforeUnavailableRoutingOrClaimAndDoesNotTouchProvider(
            ArticleRevisionMode mode) {
        ArticleModerationStateMachine stateMachine = mock(ArticleModerationStateMachine.class);
        AiCapabilityExecutor executor = mock(AiCapabilityExecutor.class);
        AiChatGateway gateway = mock(AiChatGateway.class);
        ArticleModerationWorker worker = new ArticleModerationWorker(
                stateMachine, executor, gateway, new MetroAiProperties(),
                mock(SensitiveUtils.class), new ObjectMapper(), Clock.systemUTC(), () -> mode);

        assertThat(worker.process(event())).isEqualTo(
                ArticleModerationWorker.ProcessOutcome.DEFERRED);

        verifyNoInteractions(stateMachine, executor, gateway);
    }

    @ParameterizedTest
    @EnumSource(value = ArticleRevisionMode.class, names = {"VERIFY_FENCE", "POINTER_READ"})
    void recoveryReturnsBeforeSelectingJobsOrPublishingRabbit(ArticleRevisionMode mode) {
        ArticleModerationJobMapper jobMapper = mock(ArticleModerationJobMapper.class);
        ArticleMapper articleMapper = mock(ArticleMapper.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ArticleModerationRecovery recovery = new ArticleModerationRecovery(
                jobMapper, articleMapper, rabbitTemplate, new ObjectMapper(),
                Clock.systemUTC(), new MetroAiProperties(), true, () -> mode);

        recovery.recoverDueJobs();

        verifyNoInteractions(jobMapper, articleMapper, rabbitTemplate);
    }

    @ParameterizedTest
    @EnumSource(value = ArticleRevisionMode.class, names = {"VERIFY_FENCE", "POINTER_READ"})
    void listenerFactoryCannotAutoStartInEitherFenceMode(ArticleRevisionMode mode) {
        var factory = new ArticleModerationRabbitConfiguration()
                .articleModerationRabbitListenerContainerFactory(
                        mock(SimpleRabbitListenerContainerFactoryConfigurer.class),
                        mock(ConnectionFactory.class),
                        mock(ConfirmedModerationRetryPublisher.class),
                        new MetroAiProperties(), new RabbitProperties(), () -> mode);

        assertThat(new DirectFieldAccessor(factory).getPropertyValue("autoStartup"))
                .isEqualTo(false);
    }

    @ParameterizedTest
    @EnumSource(value = ArticleRevisionMode.class, names = {"LEGACY", "SHADOW", "CUTOVER"})
    void listenerFactoryAutoStartsOutsideFenceModes(ArticleRevisionMode mode) {
        var factory = new ArticleModerationRabbitConfiguration()
                .articleModerationRabbitListenerContainerFactory(
                        mock(SimpleRabbitListenerContainerFactoryConfigurer.class),
                        mock(ConnectionFactory.class),
                        mock(ConfirmedModerationRetryPublisher.class),
                        new MetroAiProperties(), new RabbitProperties(), () -> mode);

        assertThat(new DirectFieldAccessor(factory).getPropertyValue("autoStartup"))
                .isEqualTo(true);
    }

    @ParameterizedTest
    @EnumSource(value = ArticleRevisionMode.class, names = {"LEGACY", "SHADOW", "CUTOVER"})
    void explicitGlobalListenerShutdownStillWinsOutsideFenceModes(ArticleRevisionMode mode) {
        RabbitProperties rabbitProperties = new RabbitProperties();
        rabbitProperties.getListener().getSimple().setAutoStartup(false);
        var factory = new ArticleModerationRabbitConfiguration()
                .articleModerationRabbitListenerContainerFactory(
                        mock(SimpleRabbitListenerContainerFactoryConfigurer.class),
                        mock(ConnectionFactory.class),
                        mock(ConfirmedModerationRetryPublisher.class),
                        new MetroAiProperties(), rabbitProperties, () -> mode);

        assertThat(new DirectFieldAccessor(factory).getPropertyValue("autoStartup"))
                .isEqualTo(false);
    }

    private static DomainEvent event() {
        return new DomainEvent(UUID.randomUUID(), "ARTICLE", 1L, 1L, 1L,
                DomainEventType.ARTICLE_REVISION_SUBMITTED, 1,
                new ObjectMapper().createObjectNode(), Instant.EPOCH);
    }
}
