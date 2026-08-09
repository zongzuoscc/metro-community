package cumt.zongzuo.community.ai.moderation.revision;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("article_moderation_attempt")
public class ArticleModerationAttempt {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    private Integer attemptNo;
    private String provider;
    private String model;
    private String promptVersion;
    private String inputHash;
    private String structuredOutputJson;
    private Long latencyMs;
    private String tokenUsageJson;
    private String finishReason;
    private String errorCode;
    private LocalDateTime createdAt;
}
