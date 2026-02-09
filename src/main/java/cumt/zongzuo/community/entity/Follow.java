package cumt.zongzuo.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("follow")
public class Follow {
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 粉丝ID (谁点的关注)
     */
    private Long followerId;

    /**
     * 博主ID (关注了谁)
     */
    private Long followedId;

    private String remark;      // 【新增】备注
    private String description; // 【新增】描述

    private LocalDateTime createTime;
}