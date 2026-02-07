package cumt.zongzuo.community.dto;

import lombok.Data;

@Data
public class ResetPasswordDTO {
    private String email;
    private String code;        // 邮箱验证码
    private String newPassword;
}