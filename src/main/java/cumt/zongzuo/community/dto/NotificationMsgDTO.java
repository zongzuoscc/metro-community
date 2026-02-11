package cumt.zongzuo.community.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class NotificationMsgDTO implements Serializable {
    private Long fromId;    // 发送者
    private Long toId;      // 接收者
    private Integer type;   // 1点赞 2评论 3关注 4系统
    private Long targetId;  // 关联目标ID (文章ID/评论ID)
    private String content; // 消息内容/摘要
}