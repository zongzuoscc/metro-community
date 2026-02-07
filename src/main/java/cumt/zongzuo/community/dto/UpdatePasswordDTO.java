package cumt.zongzuo.community.dto;

import lombok.Data;

@Data
public class UpdatePasswordDTO {
    private String oldPassword;
    private String newPassword;
    private String confirmPassword; // 前端传过来校验用，或者后端只接 newPassword 也可以
}