package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.CommentDTO;
import cumt.zongzuo.community.entity.Comment;
import cumt.zongzuo.community.service.CommentService;
import cumt.zongzuo.community.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import cumt.zongzuo.community.annotation.RateLimit; // 记得导包
import jakarta.validation.Valid;
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
    public Result<String> publish(@Valid @RequestBody CommentDTO dto) {
        Long userId = CurrentUser.id();
        commentService.publishComment(dto, userId);
        return Result.success("评论成功");
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        Long userId = CurrentUser.id();
        commentService.deleteComment(id, userId);
        return Result.success("删除成功");
    }
}
