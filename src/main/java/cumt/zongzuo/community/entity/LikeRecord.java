package cumt.zongzuo.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("like_record")
public class LikeRecord {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long targetId;

    /**
     * 点赞类型：1-文章，2-评论
     */
    private Integer targetType;

    private LocalDateTime createTime;
}