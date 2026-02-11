package cumt.zongzuo.community.dto;

import lombok.Data;
import java.util.List;

@Data
public class ArticleDTO {
    private Long id;
    private String title;
    private String content;
    private String summary;
    private String cover;

    // 兼容旧字段 (如果有用到)
    private Long articleId;
    private Long parentId;
    private Long targetUserId;

    /**
     * 标签列表
     */
    private List<String> tags;

    /**
     * 【核心新增】是否发布 (true=发布, false=草稿)
     */
    private Boolean isPublish;
}