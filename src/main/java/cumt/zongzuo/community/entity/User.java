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
}
