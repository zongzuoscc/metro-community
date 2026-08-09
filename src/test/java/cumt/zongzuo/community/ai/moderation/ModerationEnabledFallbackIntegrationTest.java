package cumt.zongzuo.community.ai.moderation;

import com.sun.net.httpserver.HttpServer;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.provider.AiChatGateway;
import cumt.zongzuo.community.ai.provider.DeepSeekAiChatGateway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ResourceLock("shared-rabbit-moderation-queues")
class ModerationEnabledFallbackIntegrationTest extends IntegrationTestSupport {

    private static final String LISTENER_ID = "legacyModerationSubmissionConsumer";
    private static final String AUDIT_QUEUE = "article.audit.queue";
    private static final String ES_QUEUE = "es.sync.queue";
    private static final String NOTIFICATION_QUEUE = "message.notify.queue";
    private static final long ARTICLE_ID = 8_602_001L;
    private static final long AUTHOR_ID = 8_602_101L;
    private static final AtomicInteger PROVIDER_REQUESTS = new AtomicInteger();
    private static final HttpServer PROVIDER = providerStub();

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private MetroAiProperties properties;
    @Autowired
    private AiChatGateway chatGateway;

    @DynamicPropertySource
    static void aiProviderProperties(DynamicPropertyRegistry registry) {
        registry.add("metro.ai.enabled", () -> "true");
        registry.add("metro.ai.moderation.enabled", () -> "true");
        registry.add("metro.ai.deep-seek.api-key", () -> "test-only-key");
        registry.add("metro.ai.deep-seek.base-url",
                () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort());
    }

    @BeforeAll
    void startOnlyModerationListener() {
        purgeQueues();
        MessageListenerContainer container = listenerRegistry.getListenerContainer(LISTENER_ID);
        assertThat(container).isNotNull();
        assertThat(listenerRegistry.getListenerContainers())
                .filteredOn(MessageListenerContainer::isRunning)
                .isEmpty();
        container.start();
    }

    @AfterAll
    void stopListenerAndProvider() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(LISTENER_ID);
        if (container != null) {
            container.stop();
        }
        purgeQueues();
        PROVIDER.stop(0);
    }

    @Test
    void enabledModerationAgainst503ProviderStillRoutesManuallyWithoutProviderTraffic() {
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getModeration().isEnabled()).isTrue();
        assertThat(chatGateway).isInstanceOf(DeepSeekAiChatGateway.class);
        jdbcTemplate.update("DELETE FROM article WHERE id = ?", ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO article
                    (id, title, summary, content, author_id, status, is_deleted, create_time, update_time)
                VALUES (?, 'provider unavailable', 'summary', 'body', ?, 2, 0, NOW(), NOW())
                """, ARTICLE_ID, AUTHOR_ID);

        rabbitTemplate.convertAndSend(AUDIT_QUEUE, ARTICLE_ID);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(queueMessageCount(AUDIT_QUEUE)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM article WHERE id = ?", Integer.class, ARTICLE_ID)).isEqualTo(2);
        });
        assertThat(PROVIDER_REQUESTS).hasValue(0);
        assertThat(rabbitTemplate.receive(ES_QUEUE, 100)).isNull();
        assertThat(rabbitTemplate.receive(NOTIFICATION_QUEUE, 100)).isNull();
    }

    private static HttpServer providerStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                PROVIDER_REQUESTS.incrementAndGet();
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private int queueMessageCount(String queue) {
        Object value = amqpAdmin.getQueueProperties(queue).get("QUEUE_MESSAGE_COUNT");
        return ((Number) value).intValue();
    }

    private void purgeQueues() {
        for (String queue : new String[]{AUDIT_QUEUE, "article.audit.queue.dlq", ES_QUEUE, NOTIFICATION_QUEUE}) {
            if (amqpAdmin.getQueueProperties(queue) != null) {
                amqpAdmin.purgeQueue(queue, true);
            }
        }
    }
}
