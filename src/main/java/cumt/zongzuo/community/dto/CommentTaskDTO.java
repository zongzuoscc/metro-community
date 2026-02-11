package cumt.zongzuo.community.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class CommentTaskDTO implements Serializable {
    private Long articleId; // 哪篇文章
    private boolean isAdd;  // true=增加评论(发布), false=减少评论(删除)
    /**
     * 【新增】本次操作涉及的评论数量
     * 发布评论时 = 1
     * 删除评论时 = 1 + 子评论数
     */
    private Integer count;
}