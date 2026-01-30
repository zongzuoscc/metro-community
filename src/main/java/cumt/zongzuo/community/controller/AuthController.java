package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.LoginDTO;
import cumt.zongzuo.community.dto.RegisterDTO;
import cumt.zongzuo.community.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserService userService;

    // 发送验证码
    @GetMapping("/send-code")
    public Result<String> sendCode(@RequestParam String email) {
        return userService.sendCode(email);
    }

    // 注册
    @PostMapping("/register")
    public Result<String> register(@RequestBody RegisterDTO dto) {
        return userService.register(dto);
    }

    // 登录
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginDTO dto) {
        return userService.login(dto);
    }
}