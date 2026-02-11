package cumt.zongzuo.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("comment")
public class Comment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 文章ID
     */
    private Long articleId;

    /**
     * 发送者ID
     */
    private Long userId;

    /**
     * 评论内容
     */
    private String content;

    /**
     * 父评论ID (0表示这是一条根评论)
     */
    private Long parentId;

    // 【新增】逻辑删除字段
    private Integer isDeleted;

    /**
     * 被回复的人的ID (仅子评论有效，如果是回复某人，这里存那个人ID)
     */
    private Long targetUserId;

    /**
     * 点赞数
     */
    private Integer likeCount;

    private LocalDateTime createTime;

    // =========================================================
    // 下面是“非数据库字段”，用于给前端展示数据
    // =========================================================

    /**
     * 发送者的昵称
     */
    @TableField(exist = false)
    private String username;

    /**
     * 发送者的头像
     */
    @TableField(exist = false)
    private String avatar;

    /**
     * 被回复者的昵称 (例如：回复 @张三)
     */
    @TableField(exist = false)
    private String targetUsername;

    /**
     * 子评论列表 (套娃结构：评论下面还有评论)
     */
    @TableField(exist = false)
    private List<Comment> children;
}