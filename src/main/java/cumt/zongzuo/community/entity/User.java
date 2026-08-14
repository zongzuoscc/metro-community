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

    private Integer role;
    private Integer status; // 0正常 1封禁


    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 封禁截止时间 (null 表示未封禁或永久封禁，需配合 status 判断)
     */
    private LocalDateTime banTime;

    /**
     * 账号生命周期状态。ACTIVE 可正常使用；PENDING_DELETE 处于七天反悔期；
     * DELETED 表示到期后已经脱敏并完成逻辑删除。
     */
    private String accountState;

    /** 用户提交注销申请的数据库时间。 */
    private LocalDateTime deletionRequestedAt;

    /** 注销反悔期截止时间；所有到期判断都使用数据库时间。 */
    private LocalDateTime purgeAfter;

    /** 注销状态的乐观锁版本，防止恢复与清理任务并发覆盖。 */
    private Long deletionVersion;

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

    @TableField(exist = false)
    private Boolean isFriend; // 【新增】是否互相关注(好友)

    @TableField(exist = false)
    private String remark;    // 【新增】我对他的备注

    @TableField(exist = false)
    private String description; // 【新增】我对他的描述
}
