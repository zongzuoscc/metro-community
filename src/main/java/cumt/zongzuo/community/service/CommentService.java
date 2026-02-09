package cumt.zongzuo.community.service;

import com.baomidou.mybatisplus.extension.service.IService;
import cumt.zongzuo.community.dto.CommentDTO;
import cumt.zongzuo.community.entity.Comment;

import java.util.List;

public interface CommentService extends IService<Comment> {

    /**
     * 发表评论
     * @param dto 前端传来的数据
     * @param userId 当前登录用户ID
     */
    void publishComment(CommentDTO dto, Long userId);

    /**
     * 查询某篇文章下的所有评论（树形结构）
     * @param articleId 文章ID
     * @return 组装好的评论列表
     */
    List<Comment> getCommentsByArticleId(Long articleId);

    /**
     * 删除评论 (支持本人删除 或 文章作者删除)
     * @param commentId 评论ID
     * @param userId 当前操作用户ID
     */
    void deleteComment(Long commentId, Long userId);
}