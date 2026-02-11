package cumt.zongzuo.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("report")
public class Report {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reporterId;
    private Long targetId;
    /**
     * 1-文章, 2-评论, 3-用户
     */
    private Integer targetType;
    private String reason;

    /**
     * 0-待处理, 1-已确认违规, 2-驳回举报
     */
    private Integer status;

    private LocalDateTime createTime;
    private LocalDateTime handleTime;
    private Long handlerId;
    private String result; // 处理备注

    // --- 辅助字段 (用于前端展示) ---
    @TableField(exist = false)
    private String reporterName; // 举报人名字

    @TableField(exist = false)
    private String targetSnapshot; // 被举报内容的快照(标题/评论内容)
}