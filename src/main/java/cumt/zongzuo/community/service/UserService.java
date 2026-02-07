package cumt.zongzuo.community.service;

import com.baomidou.mybatisplus.extension.service.IService;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.LoginDTO; // 或者你的DTO包
import cumt.zongzuo.community.dto.RegisterDTO;
import cumt.zongzuo.community.dto.ResetPasswordDTO;
import cumt.zongzuo.community.dto.UpdatePasswordDTO;
import cumt.zongzuo.community.entity.User;

import java.util.Map;

public interface UserService extends IService<User> {

    // 发送验证码
    Result<String> sendCode(String email);

    // 注册
    Result<String> register(RegisterDTO dto);

    // 登录
    Result<Map<String, Object>> login(LoginDTO dto);

    /**
     * 获取用户个人主页信息 (带统计数据)
     * @param targetUserId 要查看的用户ID
     * @param currentUserId 当前登录用户ID (用于判断是否关注了对方)
     */
    User getUserProfile(Long targetUserId, Long currentUserId);

    // 修改密码
    void updatePassword(Long userId, UpdatePasswordDTO dto);

    // 【新增】重置密码 (忘记密码用)
    Result<String> resetPassword(ResetPasswordDTO dto);
}