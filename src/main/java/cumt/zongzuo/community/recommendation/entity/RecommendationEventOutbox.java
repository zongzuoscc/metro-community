package cumt.zongzuo.community.recommendation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("recommendation_event_outbox")
public class RecommendationEventOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long articleId;
    private Long targetAuthorId;
    private String eventType;
    private LocalDateTime occurredAt;
    private String dedupeKey;
    private String source;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextAttemptAt;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime sentTime;

    public static RecommendationEventOutbox pending(RecommendationEventCommand command) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        RecommendationEventOutbox outbox = new RecommendationEventOutbox();
        outbox.setUserId(command.userId());
        outbox.setArticleId(command.articleId());
        outbox.setTargetAuthorId(command.targetAuthorId());
        outbox.setEventType(command.eventType().name());
        outbox.setOccurredAt(command.occurredAt());
        outbox.setDedupeKey(command.dedupeKey());
        outbox.setSource(command.source());
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outbox.setNextAttemptAt(now);
        outbox.setCreateTime(now);
        outbox.setUpdateTime(now);
        return outbox;
    }

    public RecommendationEventCommand toCommand() {
        return new RecommendationEventCommand(userId, articleId, targetAuthorId,
                RecommendationEventType.valueOf(eventType), occurredAt, dedupeKey, source);
    }
}
