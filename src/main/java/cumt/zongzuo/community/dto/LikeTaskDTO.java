package cumt.zongzuo.community.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class LikeTaskDTO implements Serializable {
    private Long userId;        // 谁点的
    private Long targetId;      // 点了哪个
    private Integer targetType; // 1文章 2评论
    private boolean isLike;     // true=点赞, false=取消点赞
}