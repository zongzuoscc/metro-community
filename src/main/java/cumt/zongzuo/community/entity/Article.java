package cumt.zongzuo.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("article")
public class Article {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String content;

    private String summary;

    private Long authorId;

    private Integer viewCount;

    private Integer likeCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableField(exist = false)
    private String authorName;

    @TableField(exist = false)
    private String authorAvatar;

    @TableField(exist = false)
    private String authorIntro;       // 作者简介

    @TableField(exist = false)
    private Long authorArticleCount;  // 作者文章总数

    @TableField(exist = false)
    private Long authorTotalLikes;    // 作者获赞总数

    @TableField(exist = false)
    private Boolean isLiked; // 当前登录用户是否已点赞
}