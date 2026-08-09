package cumt.zongzuo.community.recommendation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_article_event")
public class UserArticleEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long articleId;
    private Long targetAuthorId;
    private String eventType;
    private LocalDateTime occurredAt;
    private String dedupeKey;
    private String source;
    private LocalDateTime createTime;
}
