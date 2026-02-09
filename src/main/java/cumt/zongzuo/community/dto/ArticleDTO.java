package cumt.zongzuo.community.dto;

import lombok.Data;

@Data
public class ArticleDTO {
    private Long id; // 【新增】如果有值，说明是修改；没值说明是新增
    private String title;
    private String content;
    private String summary; // 可选，后端也可自动生成
    private String cover;   // 封面图(可选)
}