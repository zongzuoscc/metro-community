package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.service.UserService;
import cumt.zongzuo.community.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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

        // 这里的 updateById 是 MyBatis-Plus 自带的，会自动忽略 null 字段
        // 所以前端只传 avatar，就只会更新 avatar
        userService.updateById(user);

        return Result.success("修改成功");
    }
}