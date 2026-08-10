package cumt.zongzuo.community.ai.moderation.revision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.mapper.ArticleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

/** Recovers jobs whose outbox delivery was lost or whose process died before lease completion. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "metro.ai", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "metro.ai.moderation",
        name = {"enabled", "recovery-enabled"}, havingValue = "true")
public class ArticleModerationRecovery {

    private static final int BATCH_SIZE = 32;

    private final ArticleModerationJobMapper jobMapper;
    private final ArticleMapper articleMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean enabled;
    private final MetroAiProperties properties;

    public ArticleModerationRecovery(ArticleModerationJobMapper jobMapper,
                                     ArticleMapper articleMapper,
                                     RabbitTemplate rabbitTemplate,
                                     ObjectMapper objectMapper,
                                     Clock clock,
                                     MetroAiProperties properties,
                                     @Value("${metro.ai.moderation.recovery-enabled:true}")
                                     boolean enabled) {
        this.jobMapper = jobMapper;
        this.articleMapper = articleMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${metro.ai.moderation.recovery-delay-ms:30000}",
            initialDelayString = "${metro.ai.moderation.recovery-initial-delay-ms:30000}")
    public void recoverDueJobs() {
        if (!enabled || !properties.isCapabilityEnabled(AiCapability.MODERATION)) {
            return;
        }
        for (ArticleModerationJob job : jobMapper.selectRecoverable(BATCH_SIZE)) {
            try {
                rabbitTemplate.convertAndSend(RabbitConfig.ARTICLE_MODERATION_QUEUE,
                        recoveryEvent(job));
            }
            catch (RuntimeException error) {
                // Infrastructure and poison failures remain durable and are retried by the next scan.
                log.warn("Moderation recovery deferred for job {} ({})", job.getId(),
                        error.getClass().getSimpleName());
            }
        }
    }

    private DomainEvent recoveryEvent(ArticleModerationJob job) {
        Article article = articleMapper.selectById(job.getArticleId());
        long aggregateVersion = article == null || article.getLockVersion() == null
                ? 0L : article.getLockVersion();
        long lifecycleEpoch = article == null || article.getLifecycleEpoch() == null
                ? 0L : article.getLifecycleEpoch();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("articleId", job.getArticleId());
        payload.put("revisionId", job.getRevisionId());
        payload.put("moderationJobId", job.getId());
        payload.put("contentHash", job.getContentHash());
        UUID eventId = UUID.nameUUIDFromBytes(("moderation-recovery:" + job.getId() + ':'
                + job.getLockVersion()).getBytes(StandardCharsets.UTF_8));
        return new DomainEvent(eventId, "ARTICLE", job.getArticleId(), aggregateVersion,
                lifecycleEpoch, DomainEventType.ARTICLE_REVISION_SUBMITTED, 1, payload,
                clock.instant());
    }
}
