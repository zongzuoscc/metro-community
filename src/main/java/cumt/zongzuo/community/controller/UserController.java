package cumt.zongzuo.community.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.UpdatePasswordDTO;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.service.UserService;
import cumt.zongzuo.community.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    // 获取当前用户信息
    @GetMapping("/info")
    public Result<User> getUserInfo(@RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        User user = userService.getById(userId);
        // 密码脱敏
        user.setPassword(null);
        return Result.success(user);
    }

    // 更新用户信息 (修改头像、昵称、简介)
    @PostMapping("/update")
    public Result<String> updateUserInfo(@RequestBody User user, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        // 只能修改自己的信息
        user.setId(userId);

        // 【安全修正】强制将敏感字段置空，防止被恶意修改
        user.setPassword(null);
        user.setEmail(null); // 邮箱修改通常需要验证码，不应在这里直接改
        // user.setRole(null); // 如果有角色字段也要置空

        userService.updateById(user);

        // 【新增】清除缓存，保证下次读取是新的
        userService.clearUserCache(user.getId());
        return Result.success("更新成功");
    }

    // 获取任意用户的个人主页信息
    // GET /api/user/profile/{userId}
    @GetMapping("/profile/{userId}")
    public Result<User> getUserProfile(@PathVariable Long userId, @RequestHeader(value = "token", required = false) String token) {
        Long currentUserId = null;
        if (token != null && !token.isEmpty()) {
            try {
                currentUserId = JwtUtils.getUserId(token);
            } catch (Exception e) {}
        }

        User user = userService.getUserProfile(userId, currentUserId);
        return Result.success(user);
    }

    @PostMapping("/password")
    public Result<String> updatePassword(@RequestBody UpdatePasswordDTO dto, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        try {
            userService.updatePassword(userId, dto);
            return Result.success("密码修改成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    // 【新增】搜索用户接口
    @GetMapping("/search")
    public Result<Page<User>> search(@RequestParam String keyword,
                                     @RequestParam(defaultValue = "1") int page) {
        return Result.success(userService.searchUsers(keyword, page, 10));
    }

    /**
     * 【管理员】获取用户列表
     */
    @GetMapping("/admin/list")
    public Result<Page<User>> getUserList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestHeader("token") String token) {

        // 1. 鉴权 (校验是否管理员)
        checkAdminRole(token);

        // 2. 调用 Service
        return Result.success(userService.getUserList(page, size, keyword));
    }

    /**
     * 【管理员】封禁/解封用户
     */
    @PostMapping("/admin/status")
    public Result<String> updateUserStatus(
            @RequestBody Map<String, Object> params,
            @RequestHeader("token") String token) {

        // 1. 鉴权
        checkAdminRole(token);

        // 2. 解析参数
        Long userId = Long.valueOf(params.get("userId").toString());
        Integer status = Integer.valueOf(params.get("status").toString());

        // 3. 调用 Service
        userService.updateUserStatus(userId, status);

        return Result.success("操作成功");
    }

    // --- 提取公共鉴权方法 (私有) ---
    private void checkAdminRole(String token) {
        Long currentUserId = JwtUtils.getUserId(token);
        // 这里可以直接查库，也可以查缓存
        // User currentUser = userService.getById(currentUserId);
        // 为了性能最好是用 getUserCached，但因为是管理后台，查库也无所谓
        User currentUser = userService.getUserCached(currentUserId);

        if (currentUser == null || currentUser.getRole() != 1) {
            throw new RuntimeException("无权访问"); // 全局异常处理会捕获
        }
    }
}