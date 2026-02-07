package cumt.zongzuo.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("chat_msg")
public class ChatMsg {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fromId;
    private Long toId;
    private String content;
    private Integer status; // 0-未读, 1-已读
    private LocalDateTime createTime;

    // --- 扩展字段：发送者信息 (用于前端展示) ---
    @TableField(exist = false)
    private String fromAvatar;
    @TableField(exist = false)
    private String fromUsername;
}