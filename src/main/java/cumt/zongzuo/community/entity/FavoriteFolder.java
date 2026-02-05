package cumt.zongzuo.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("favorite_folder")
public class FavoriteFolder {
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户
     */
    private Long userId;

    /**
     * 收藏夹名称
     */
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 是否公开 (1-公开, 0-私密)
     */
    private Integer isPublic;

    private LocalDateTime createTime;

    // --- 非数据库字段 ---

    /**
     * 该文件夹下的文章数量 (接收 SQL count(*) 的结果)
     */
    @TableField(exist = false)
    private Long count;
}