package cumt.zongzuo.community.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("article")
public class Article {
    private Long id;
    private String title;
    private String summary;
    private String content; // 列表页通常不需要查内容，为了简单先写上
    private Long authorId;
    private String authorName;
    private Integer viewCount;
    private Integer likeCount;
    private LocalDateTime createTime;
}