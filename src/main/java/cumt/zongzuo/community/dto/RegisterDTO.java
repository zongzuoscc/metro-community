package cumt.zongzuo.community.dto;
import lombok.Data;

@Data
public class RegisterDTO {
    private String email;
    private String code;     // 验证码
    private String username;
    private String password;
}