package cumt.zongzuo.community.dto;

import lombok.Data;

@Data
public class ArticleDTO {
    private String title;
    private String content;
    private String summary; // 摘要可以前端生成，也可以后端截取
}