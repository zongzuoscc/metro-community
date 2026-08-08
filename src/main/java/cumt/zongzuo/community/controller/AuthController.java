package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.LoginDTO;
import cumt.zongzuo.community.dto.RegisterDTO;
import cumt.zongzuo.community.dto.ResetPasswordDTO;
import cumt.zongzuo.community.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    @Autowired
    private UserService userService;

    // 发送验证码
    @GetMapping("/send-code")
    public Result<String> sendCode(@RequestParam @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确")
                                   @Size(max = 254, message = "邮箱长度不能超过254个字符") String email) {
        return userService.sendCode(email);
    }

    // 注册
    @PostMapping("/register")
    public Result<String> register(@Valid @RequestBody RegisterDTO dto) {
        return userService.register(dto);
    }

    // 登录
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginDTO dto) {
        return userService.login(dto);
    }

    @PostMapping("/reset-password")
    public Result<String> resetPassword(@Valid @RequestBody ResetPasswordDTO dto) {
        return userService.resetPassword(dto);
    }
}
