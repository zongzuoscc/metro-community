package cumt.zongzuo.community.article.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("article_revision")
public class ArticleRevision {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long articleId;
    private Long revisionNo;
    private String title;
    private String summary;
    private String bodyMarkdown;
    private String bodyPlain;
    private String cover;
    private String tagsJson;
    private String contentHash;
    private Long sourceDraftVersion;
    private Long createdBy;
    private LocalDateTime createdAt;
}
