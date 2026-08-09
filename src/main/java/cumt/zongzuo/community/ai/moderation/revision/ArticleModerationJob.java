package cumt.zongzuo.community.ai.moderation.revision;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("article_moderation_job")
public class ArticleModerationJob {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long articleId;
    private Long revisionId;
    private String contentHash;
    private String state;
    private String modelDecision;
    private BigDecimal riskScore;
    private String policyHitsJson;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String lastError;
    private Long reviewerId;
    private String reviewReason;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long lockVersion;
}
