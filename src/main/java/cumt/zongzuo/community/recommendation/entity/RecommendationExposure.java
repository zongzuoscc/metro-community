package cumt.zongzuo.community.recommendation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("recommendation_exposure")
public class RecommendationExposure {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long articleId;
    private String sessionId;
    private String source;
    private Double tagAffinity;
    private Double authorAffinity;
    private Double similarScore;
    private Double heatScore;
    private Double freshnessScore;
    private Double sourceFollow;
    private Double sourceTag;
    private Double sourceSimilar;
    private Double sourceExplore;
    private Double baselineScore;
    private LocalDateTime exposedAt;
    private LocalDateTime createTime;
}
