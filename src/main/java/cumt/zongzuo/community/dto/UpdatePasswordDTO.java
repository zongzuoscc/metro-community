package cumt.zongzuo.community.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class UpdatePasswordDTO {
    @NotBlank(message = "旧密码不能为空")
    @Size(max = 72, message = "密码长度不合法")
    private String oldPassword;
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 72, message = "密码长度应为8到72个字符")
    private String newPassword;
    @NotBlank(message = "确认密码不能为空")
    @Size(max = 72, message = "密码长度不合法")
    private String confirmPassword;
}
