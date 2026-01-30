package cumt.zongzuo.community.service;

import com.baomidou.mybatisplus.extension.service.IService;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.LoginDTO; // 或者你的DTO包
import cumt.zongzuo.community.dto.RegisterDTO;
import cumt.zongzuo.community.entity.User;

import java.util.Map;

public interface UserService extends IService<User> {

    // 发送验证码
    Result<String> sendCode(String email);

    // 注册
    Result<String> register(RegisterDTO dto);

    // 登录
    Result<Map<String, Object>> login(LoginDTO dto);
}