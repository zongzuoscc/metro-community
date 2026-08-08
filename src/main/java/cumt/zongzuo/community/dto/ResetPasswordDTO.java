package cumt.zongzuo.community.dto;

import lombok.Data;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class ResetPasswordDTO {
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "邮箱长度不能超过254个字符")
    private String email;
    @NotBlank(message = "验证码不能为空")
    @Size(max = 16, message = "验证码长度不合法")
    private String code;
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 72, message = "密码长度应为8到72个字符")
    private String newPassword;
}
