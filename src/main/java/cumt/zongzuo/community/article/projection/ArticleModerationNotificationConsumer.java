package cumt.zongzuo.community.article.projection;

import com.fasterxml.jackson.databind.JsonNode;
import cumt.zongzuo.community.ai.moderation.revision.ArticleModerationJob;
import cumt.zongzuo.community.ai.moderation.revision.ArticleModerationJobMapper;
import cumt.zongzuo.community.article.model.ArticleRevision;
import cumt.zongzuo.community.article.persistence.ArticleRevisionMapper;
import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.entity.Message;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.projection.ConsumerInboxMapper;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.MessageMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Component
public class ArticleModerationNotificationConsumer {

    private final ArticleModerationNotificationProjector projector;

    public ArticleModerationNotificationConsumer(ArticleModerationNotificationProjector projector) {
        this.projector = projector;
    }

    @RabbitListener(id = "articleModerationNotificationConsumer",
            queues = RabbitConfig.ARTICLE_MODERATION_NOTIFICATION_QUEUE)
    public void consume(DomainEvent event) {
        projector.project(event);
    }
}

@Service
class ArticleModerationNotificationProjector {

    static final String CONSUMER = "article-moderation-notification";

    private final ConsumerInboxMapper inboxMapper;
    private final MessageMapper messageMapper;
    private final ArticleMapper articleMapper;
    private final ArticleRevisionMapper revisionMapper;
    private final ArticleModerationJobMapper jobMapper;

    ArticleModerationNotificationProjector(ConsumerInboxMapper inboxMapper,
                                           MessageMapper messageMapper,
                                           ArticleMapper articleMapper,
                                           ArticleRevisionMapper revisionMapper,
                                           ArticleModerationJobMapper jobMapper) {
        this.inboxMapper = inboxMapper;
        this.messageMapper = messageMapper;
        this.articleMapper = articleMapper;
        this.revisionMapper = revisionMapper;
        this.jobMapper = jobMapper;
    }

    @Transactional
    public void project(DomainEvent event) {
        requireRoutedEvent(event);
        if (inboxMapper.exists(CONSUMER, event.eventId())) {
            return;
        }

        boolean noOp = isExplicitNoOp(event);
        NotificationFact fact = noOp ? null : rehydrateHumanDecision(event);
        String resultHash;
        if (fact != null) {
            // Deliberate ordering: the durable delivery fact is inserted before Inbox,
            // and both writes share this local transaction.
            messageMapper.insertEventMessage(toMessage(event, fact));
            resultHash = hash("message:" + event.eventId() + ":" + fact.job().getState());
        } else {
            // RESTORED and report-originated events share routing keys but are not
            // moderation decisions. They are deterministic no-ops, still deduped.
            resultHash = hash("noop:" + event.eventId());
        }
        inboxMapper.insertIgnore(CONSUMER, event.eventId(), resultHash);
    }

    private NotificationFact rehydrateHumanDecision(DomainEvent event) {
        JsonNode payload = event.payload();
        if (!payload.hasNonNull("articleId") || !payload.hasNonNull("revisionId")
                || !payload.hasNonNull("moderationJobId") || !payload.hasNonNull("contentHash")
                || !payload.get("articleId").canConvertToLong()
                || !payload.get("revisionId").canConvertToLong()
                || !payload.get("moderationJobId").canConvertToLong()) {
            throw new IllegalStateException("moderation notification event tuple is incomplete");
        }
        long articleId = payload.get("articleId").longValue();
        long revisionId = payload.get("revisionId").longValue();
        long jobId = payload.get("moderationJobId").longValue();
        String contentHash = payload.get("contentHash").textValue();
        if (articleId != event.aggregateId() || revisionId <= 0 || jobId <= 0
                || contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("moderation notification event tuple is invalid");
        }

        ArticleModerationJob job = jobMapper.selectById(jobId);
        ArticleRevision revision = revisionMapper.selectById(revisionId);
        Article article = articleMapper.selectById(articleId);
        String requiredState = event.eventType() == DomainEventType.ARTICLE_REVISION_PUBLISHED
                ? "HUMAN_APPROVED" : "HUMAN_REJECTED";
        if (job == null || revision == null || article == null
                || !Long.valueOf(articleId).equals(job.getArticleId())
                || !Long.valueOf(revisionId).equals(job.getRevisionId())
                || !contentHash.equals(job.getContentHash())
                || !requiredState.equals(job.getState())
                || job.getReviewerId() == null
                || !Long.valueOf(articleId).equals(revision.getArticleId())
                || !contentHash.equals(revision.getContentHash())
                || article.getAuthorId() == null) {
            throw new IllegalStateException("moderation notification tuple no longer matches MySQL truth");
        }
        return new NotificationFact(article, revision, job);
    }

    private static boolean isExplicitNoOp(DomainEvent event) {
        JsonNode payload = event.payload();
        String transition = payload.path("transition").isTextual()
                ? payload.path("transition").textValue() : null;
        boolean allowed = event.eventType() == DomainEventType.ARTICLE_REVISION_PUBLISHED
                && "RESTORED".equals(transition)
                || event.eventType() == DomainEventType.ARTICLE_REVISION_REJECTED
                && "REPORT_CONFIRMED".equals(transition);
        if (!allowed) {
            return false;
        }
        if (payload.has("moderationJobId") || payload.has("contentHash")) {
            throw new IllegalStateException("explicit notification no-op must not carry a decision tuple");
        }
        if (!payload.hasNonNull("articleId") || !payload.get("articleId").canConvertToLong()
                || payload.get("articleId").longValue() != event.aggregateId()) {
            throw new IllegalStateException("explicit notification no-op has invalid article identity");
        }
        if ("RESTORED".equals(transition)) {
            if (!payload.has("publishedRevisionId")
                    || !isPositiveLong(payload.get("publishedRevisionId"))
                    || payload.has("revisionId")
                    || payload.has("oldPublishedRevisionId")
                    || payload.has("newPublishedRevisionId")) {
                throw new IllegalStateException("restored notification no-op shape is invalid");
            }
        } else if (!payload.has("revisionId")
                || !isNullOrPositiveLong(payload.get("revisionId"))
                || !payload.has("oldPublishedRevisionId")
                || !payload.get("oldPublishedRevisionId").isNull()
                || !payload.has("newPublishedRevisionId")
                || !payload.get("newPublishedRevisionId").isNull()
                || payload.has("publishedRevisionId")) {
            // A report with a non-null old pointer is ARTICLE_UNPUBLISHED and is not
            // routed here. This is the exact ARTICLE_REVISION_REJECTED writer shape.
            throw new IllegalStateException("report notification no-op shape is invalid");
        }
        return true;
    }

    private static boolean isNullOrPositiveLong(JsonNode value) {
        return value != null && (value.isNull()
                || value.canConvertToLong() && value.longValue() > 0);
    }

    private static boolean isPositiveLong(JsonNode value) {
        return value != null && value.canConvertToLong() && value.longValue() > 0;
    }

    private static Message toMessage(DomainEvent event, NotificationFact fact) {
        boolean approved = event.eventType() == DomainEventType.ARTICLE_REVISION_PUBLISHED;
        Message message = new Message();
        message.setFromId(fact.job().getReviewerId());
        message.setToId(fact.article().getAuthorId());
        message.setType(4);
        message.setTargetId(fact.article().getId());
        if (approved) {
            message.setContent(boundMessage("🎉 恭喜！您的文章《" + fact.revision().getTitle()
                    + "》已通过人工审核并成功发布。"));
        } else {
            String reason = StringUtils.hasText(fact.job().getReviewReason())
                    ? fact.job().getReviewReason() : "存在违规内容";
            message.setContent(boundMessage("⚠️ 抱歉，您的文章《" + fact.revision().getTitle()
                    + "》未通过人工审核。原因：" + reason + "。请修改后重新发布。"));
        }
        message.setStatus(0);
        message.setCreateTime(LocalDateTime.now());
        message.setSourceEventId(event.eventId());
        return message;
    }

    private static String boundMessage(String value) {
        int maximumCodePoints = 500;
        if (value.codePointCount(0, value.length()) <= maximumCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }

    private static void requireRoutedEvent(DomainEvent event) {
        if (event == null || !"ARTICLE".equals(event.aggregateType())
                || (event.eventType() != DomainEventType.ARTICLE_REVISION_PUBLISHED
                && event.eventType() != DomainEventType.ARTICLE_REVISION_REJECTED)) {
            throw new IllegalArgumentException("unsupported moderation notification event");
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record NotificationFact(Article article, ArticleRevision revision,
                                    ArticleModerationJob job) {
    }
}
