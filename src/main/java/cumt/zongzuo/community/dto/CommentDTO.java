package cumt.zongzuo.community.dto;

import lombok.Data;

@Data
public class CommentDTO {
    /**
     * 评论内容
     */
    private String content;

    /**
     * 文章ID
     */
    private Long articleId;

    /**
     * 父评论ID (如果是根评论，传0；如果是回复别人，传那条根评论的ID)
     */
    private Long parentId;

    /**
     * 被回复的人的ID (可选，仅在二级回复时需要)
     */
    private Long targetUserId;
}