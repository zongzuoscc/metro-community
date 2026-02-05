package cumt.zongzuo.community.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("sys_user") // 对应数据库表名
public class User implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String email;

    private String avatar;

    private String intro;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic // 逻辑删除注解
    private Integer deleted;

    // --- 统计数据 (非数据库字段) ---
    @TableField(exist = false)
    private Long articleCount;   // 文章数

    @TableField(exist = false)
    private Long likeCount;      // 获赞数

    @TableField(exist = false)
    private Long followingCount; // 关注数

    @TableField(exist = false)
    private Long fanCount;       // 粉丝数

    // 该用户是否被当前登录用户关注 (用于查看别人主页时，显示"关注"还是"已关注"按钮)
    @TableField(exist = false)
    private Boolean isFollowed;
}
