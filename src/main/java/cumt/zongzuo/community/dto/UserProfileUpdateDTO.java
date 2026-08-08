package cumt.zongzuo.community.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * The only fields a user may edit on their own profile. Account role, status,
 * email, password and audit fields deliberately do not appear here.
 */
@Data
public class UserProfileUpdateDTO {

    @Size(min = 2, max = 30, message = "用户名长度应为2到30个字符")
    private String username;

    @Size(max = 2048, message = "头像地址不能超过2048个字符")
    private String avatar;

    @Size(max = 500, message = "个人简介不能超过500个字符")
    private String intro;
}
