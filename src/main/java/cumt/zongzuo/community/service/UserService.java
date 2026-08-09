package cumt.zongzuo.community.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.LoginDTO; // 或者你的DTO包
import cumt.zongzuo.community.dto.RegisterDTO;
import cumt.zongzuo.community.dto.ResetPasswordDTO;
import cumt.zongzuo.community.dto.UpdatePasswordDTO;
import cumt.zongzuo.community.entity.User;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

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

    // 【新增】搜索用户 (根据用户名或简介模糊查询)
    Page<User> searchUsers(String keyword, int page, int size);

    // ============ 【新增】缓存相关接口 ============

    /**
     * 获取用户信息 (优先查缓存)
     */
    User getUserCached(Long userId);

    /**
     * 批量获取用户信息 (优先查缓存，防击穿)
     * @return Map<UserId, User> 方便调用者直接 .get(id)
     */
    Map<Long, User> getUserMapCached(Set<Long> userIds);

    /**
     * 清除用户缓存 (更新资料时调用)
     */
    void clearUserCache(Long userId);

    // 【新增】管理员：搜索用户列表
    Page<User> getUserList(int page, int size, String keyword);

    // 【新增】管理员：封禁/解封用户
    void updateUserStatus(Long userId, Integer status, LocalDateTime banTime);
}