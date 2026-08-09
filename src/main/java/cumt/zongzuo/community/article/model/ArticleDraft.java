package cumt.zongzuo.community.article.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("article_draft")
public class ArticleDraft {

    @TableId(value = "article_id", type = IdType.INPUT)
    private Long articleId;
    private Long userId;
    private Long draftVersion;
    private String title;
    private String summary;
    private String bodyMarkdown;
    private String bodyPlain;
    private String cover;
    private String tagsJson;
    private String contentHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long lockVersion;
}
