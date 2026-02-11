package cumt.zongzuo.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("article")
public class Article {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String content;

    private String summary;

    private String cover;

    private Long authorId;

    private Integer viewCount;

    private Integer likeCount;
    // 【新增】
    private Integer commentCount;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer status;

    /**
     * 是否软删除: 0-否, 1-是
     */
    private Integer isDeleted;

    /**
     * 删除时间 (用于计算过期)
     */
    private LocalDateTime deleteTime;

    @TableField(exist = false)
    private String authorName;
    @TableField(exist = false)
    private String authorAvatar;
    @TableField(exist = false)
    private String authorIntro;
    @TableField(exist = false)
    private Long authorArticleCount;
    @TableField(exist = false)
    private Long authorTotalLikes;
    @TableField(exist = false)
    private Boolean isLiked;
    /**
     * 文章标签列表 (展示用)
     */
    @TableField(exist = false)
    private List<String> tagList;
}