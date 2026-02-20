package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.CommentDTO;
import cumt.zongzuo.community.entity.Comment;
import cumt.zongzuo.community.service.CommentService;
import cumt.zongzuo.community.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import cumt.zongzuo.community.annotation.RateLimit; // 记得导包
import java.util.List;

@RestController
@RequestMapping("/api/comment")
public class CommentController {

    @Autowired
    private CommentService commentService;

    // 获取文章评论列表 (树形结构)
    @GetMapping("/list/{articleId}")
    public Result<List<Comment>> getComments(@PathVariable Long articleId) {
        List<Comment> list = commentService.getCommentsByArticleId(articleId);
        return Result.success(list);
    }

    // 发表评论 (支持根评论和子回复)
    @PostMapping("/publish")
    @RateLimit(name = "publish_comment", time = 5, count = 1)
    public Result<String> publish(@RequestBody CommentDTO dto, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        // 简单的校验
        if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            return Result.error("评论内容不能为空");
        }

        commentService.publishComment(dto, userId);
        return Result.success("评论成功");
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        commentService.deleteComment(id, userId);
        return Result.success("删除成功");
    }
}