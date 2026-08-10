package cumt.zongzuo.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "message", autoResultMap = true)
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 发送者ID (谁点的赞/评的论)
     */
    private Long fromId;

    /**
     * 接收者ID (通知给谁)
     */
    private Long toId;

    /**
     * 消息类型: 1-点赞, 2-评论, 3-关注, 4-系统通知
     */
    private Integer type;

    /**
     * 关联的目标ID (例如文章ID)
     * 用于前端点击通知跳转
     */
    private Long targetId;

    /**
     * 附加内容 (例如评论的具体内容)
     */
    private String content;

    /**
     * 状态: 0-未读, 1-已读
     */
    private Integer status;

    private LocalDateTime createTime;

    /** Original domain event UUID; null is retained for legacy notifications. */
    @TableField(typeHandler = cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler.class)
    private java.util.UUID sourceEventId;

    // --- 非数据库字段 (用于前端展示) ---

    @TableField(exist = false)
    private String fromUsername;

    @TableField(exist = false)
    private String fromAvatar;
}
