package cumt.zongzuo.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cumt.zongzuo.community.dto.CommentDTO;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.entity.Comment;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.CommentMapper;
import cumt.zongzuo.community.mapper.UserMapper;
import cumt.zongzuo.community.service.CommentService;
import cumt.zongzuo.community.service.MessageService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements CommentService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private MessageService messageService;
    @Autowired
    private ArticleMapper articleMapper;

    @Override
    public void publishComment(CommentDTO dto, Long userId) {
        Comment comment = new Comment();
        // 1. 复制属性 (content, articleId, parentId, targetUserId)
        BeanUtils.copyProperties(dto, comment);

        // 2. 补全属性
        comment.setUserId(userId);
        comment.setCreateTime(LocalDateTime.now());
        comment.setLikeCount(0);

        // 3. 简单的校验
        if (comment.getParentId() == null) {
            comment.setParentId(0L); // 默认为根评论
        }

        save(comment);
        Long receiverId;
        if (comment.getTargetUserId() != null) {
            // 回复某人
            receiverId = comment.getTargetUserId();
        } else {
            // 回复文章，找文章作者
            Article article = articleMapper.selectById(comment.getArticleId());
            receiverId = (article != null) ? article.getAuthorId() : null;
        }

        if (receiverId != null) {
            // type=2 代表评论
            // content 截取前30个字提示一下
            String summary = comment.getContent().length() > 30
                    ? comment.getContent().substring(0, 30) + "..."
                    : comment.getContent();

            // targetId 我们统一存文章ID，这样点击通知能跳到文章详情
            messageService.send(userId, receiverId, 2, comment.getArticleId(), summary);
        }
    }

    @Override
    public List<Comment> getCommentsByArticleId(Long articleId) {
        // 1. 查出该文章下的所有评论 (按时间正序，楼层效果)
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getArticleId, articleId);
        wrapper.orderByAsc(Comment::getCreateTime);
        List<Comment> allComments = list(wrapper);

        if (allComments.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 【核心优化】批量查询用户信息 (发送者 + 被回复者)
        // 如果不这样做，每一条评论都要去查一次 User 表，100条评论就是101次查询，性能极差。
        Set<Long> userIds = new HashSet<>();
        for (Comment c : allComments) {
            userIds.add(c.getUserId());         // 发送者
            if (c.getTargetUserId() != null) {
                userIds.add(c.getTargetUserId()); // 被回复的人
            }
        }

        List<User> users = userMapper.selectBatchIds(userIds);
        // 转成 Map<UserId, User> 方便快速查找
        Map<Long, User> userMap = users.stream().collect(Collectors.toMap(User::getId, Function.identity()));

        // 3. 填充用户信息到 Comment 对象中
        for (Comment c : allComments) {
            // 填发送者
            User author = userMap.get(c.getUserId());
            if (author != null) {
                c.setUsername(author.getUsername());
                c.setAvatar(author.getAvatar());
            } else {
                c.setUsername("注销用户");
                c.setAvatar("https://cube.elemecdn.com/9/c2/f0ee8a3c7c9638a54940382568c9dpng.png");
            }

            // 填被回复者
            if (c.getTargetUserId() != null) {
                User target = userMap.get(c.getTargetUserId());
                if (target != null) {
                    c.setTargetUsername(target.getUsername());
                }
            }
        }

        // 4. 【核心逻辑】构建树形结构 (根评论 -> 子评论列表)
        // 思路：先把所有评论分成两组：根评论(parentId=0) 和 子评论(parentId!=0)

        List<Comment> rootComments = allComments.stream()
                .filter(c -> c.getParentId() == 0)
                .collect(Collectors.toList());

        // 将子评论按 parentId 分组 -> Map<ParentId, List<Child>>
        Map<Long, List<Comment>> childrenMap = allComments.stream()
                .filter(c -> c.getParentId() != 0)
                .collect(Collectors.groupingBy(Comment::getParentId));

        // 遍历根评论，把对应的子评论塞进去
        for (Comment root : rootComments) {
            List<Comment> children = childrenMap.get(root.getId());
            if (children == null) {
                children = new ArrayList<>();
            }
            root.setChildren(children);
        }

        return rootComments;
    }

    /**
     * 删除评论 (修复版：使用 parentId)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long commentId, Long userId) {
        // 1. 查询评论是否存在
        Comment comment = getById(commentId);
        if (comment == null) {
            throw new RuntimeException("评论不存在");
        }

        // 2. 查询所属文章 (用于校验文章作者权限)
        Article article = articleMapper.selectById(comment.getArticleId());
        if (article == null) {
            throw new RuntimeException("关联文章不存在");
        }

        // 3. 权限校验
        // 规则：当前用户是评论发布者 OR 当前用户是文章作者
        boolean isSelf = comment.getUserId().equals(userId);
        boolean isAuthor = article.getAuthorId().equals(userId);

        if (!isSelf && !isAuthor) {
            throw new RuntimeException("无权删除该评论");
        }

        // 4. 收集需要删除的评论ID
        List<Long> deleteIds = new ArrayList<>();
        deleteIds.add(commentId);

        // 5. 判断是否是根评论 (parentId == 0)
        // 如果是根评论，需要删除该楼层下的所有子评论
        if (comment.getParentId() == null || comment.getParentId() == 0) {
            // 查询所有 parentId = 当前评论ID 的子评论
            List<Comment> children = list(new QueryWrapper<Comment>().eq("parent_id", commentId));
            if (children != null && !children.isEmpty()) {
                List<Long> childIds = children.stream().map(Comment::getId).collect(Collectors.toList());
                deleteIds.addAll(childIds);
            }
        }

        // 6. 批量删除
        removeBatchByIds(deleteIds);

        // 7. 更新文章评论数 (减去实际删除的数量)
        int deleteCount = deleteIds.size();
        if (deleteCount > 0) {
            UpdateWrapper<Article> updateWrapper = new UpdateWrapper<>();
            // 使用 SQL 原子减，并防止减为负数
            updateWrapper.setSql("comment_count = GREATEST(comment_count - " + deleteCount + ", 0)");
            updateWrapper.eq("id", article.getId());
            articleMapper.update(null, updateWrapper);
        }
    }
}