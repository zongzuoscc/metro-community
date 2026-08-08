package cumt.zongzuo.community.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Data
public class CommentDTO {
    /**
     * 评论内容
     */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论内容不能超过1000个字符")
    private String content;

    /**
     * 文章ID
     */
    @NotNull(message = "文章ID不能为空")
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
