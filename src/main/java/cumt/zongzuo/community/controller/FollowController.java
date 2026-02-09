package cumt.zongzuo.community.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.service.FollowService;
import cumt.zongzuo.community.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/follow")
public class FollowController {

    @Autowired
    private FollowService followService;

    // 关注或取消关注
    @PostMapping("/{followedId}")
    public Result<String> follow(@PathVariable Long followedId, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);

        // 【核心修改】捕获业务异常，不再直接抛出 500 错误
        try {
            followService.follow(userId, followedId);
            return Result.success("操作成功");
        } catch (RuntimeException e) {
            // 捕获到 "不能关注自己" 等异常，封装成 Result 返回
            // 这样 HTTP 状态码是 200，前端能收到具体的 e.getMessage()
            return Result.error(e.getMessage());
        }
    }

    // 检查是否已关注
    @GetMapping("/check/{followedId}")
    public Result<Boolean> check(@PathVariable Long followedId, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        boolean isFollowed = followService.isFollowed(userId, followedId);
        return Result.success(isFollowed);
    }

    // 获取关注列表 (分页)
    // GET /api/follow/following/{userId}?page=1&size=20
    @GetMapping("/following/{userId}")
    public Result<Page<User>> getUserFollowings(@PathVariable Long userId,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        Page<User> list = followService.getUserFollowings(userId, page, size);
        return Result.success(list);
    }

    // 获取粉丝列表 (分页)
    // GET /api/follow/fans/{userId}?page=1&size=20
    @GetMapping("/fans/{userId}")
    public Result<Page<User>> getUserFans(@PathVariable Long userId,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        Page<User> list = followService.getUserFans(userId, page, size);
        return Result.success(list);
    }
    // 设置备注
    @PostMapping("/remark")
    public Result<String> setRemark(@RequestParam Long targetId,
                                    @RequestParam String remark,
                                    @RequestParam(required = false) String description,
                                    @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        followService.updateRemark(userId, targetId, remark, description);
        return Result.success("设置成功");
    }

}