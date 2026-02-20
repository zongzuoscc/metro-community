package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.annotation.RateLimit;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.service.LikeService;
import cumt.zongzuo.community.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/like")
public class LikeController {

    @Autowired
    private LikeService likeService;

    // 点赞 / 取消点赞
    // POST /api/like?targetId=1&targetType=1
    @PostMapping
    @RateLimit(name = "do_like", time = 1, count = 5)
    public Result<String> like(@RequestParam Long targetId,
                               @RequestParam Integer targetType,
                               @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        likeService.like(userId, targetId, targetType);
        return Result.success("操作成功");
    }

    // 查询点赞状态
    // GET /api/like/check?targetId=1&targetType=1
    @GetMapping("/check")
    public Result<Boolean> checkIsLiked(@RequestParam Long targetId,
                                        @RequestParam Integer targetType,
                                        @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        boolean isLiked = likeService.isLiked(userId, targetId, targetType);
        return Result.success(isLiked);
    }
}