package cumt.zongzuo.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cumt.zongzuo.community.dto.CommentDTO;
import cumt.zongzuo.community.dto.CommentTaskDTO;
import cumt.zongzuo.community.dto.NotificationMsgDTO;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.entity.Comment;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.CommentMapper;
import cumt.zongzuo.community.mapper.UserMapper;
import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventType;
import cumt.zongzuo.community.recommendation.service.RecommendationEventPublisher;
import cumt.zongzuo.community.service.CommentService;
import cumt.zongzuo.community.service.MessageService;
import cumt.zongzuo.community.service.UserService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    private RabbitTemplate rabbitTemplate; // 【新增注入】

    @Autowired
    private MessageService messageService;


    @Autowired
    private UserService userService;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private RecommendationEventPublisher recommendationEventPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class) // 建议加上事务
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

        // 4. 保存评论
        boolean saved = save(comment);

        if (saved) {
            recommendationEventPublisher.publishAfterCommit(new RecommendationEventCommand(
                    userId,
                    comment.getArticleId(),
                    null,
                    RecommendationEventType.COMMENT,
                    LocalDateTime.now(),
                    "comment:" + comment.getId(),
                    "comment"));
        }

        // 5. 【核心修复】更新文章评论数 +1
        CommentTaskDTO task = new CommentTaskDTO();
        task.setArticleId(dto.getArticleId());
        task.setAdd(true);
        task.setCount(1);
        rabbitTemplate.convertAndSend("comment.task.queue", task);

        // 6. 【修改】异步发送通知
        Long receiverId;
        if (comment.getTargetUserId() != null) {
            receiverId = comment.getTargetUserId();
        } else {
            Article article = articleMapper.selectById(comment.getArticleId());
            receiverId = (article != null) ? article.getAuthorId() : null;
        }

        if (receiverId != null) {
            // 构建 DTO
            NotificationMsgDTO msg = new NotificationMsgDTO();
            msg.setFromId(userId);
            msg.setToId(receiverId);
            msg.setType(2); // 评论
            msg.setTargetId(comment.getArticleId());

            String summary = comment.getContent().length() > 30
                    ? comment.getContent().substring(0, 30) + "..."
                    : comment.getContent();
            msg.setContent(summary);

            // 发送到 MQ
            rabbitTemplate.convertAndSend("message.notify.queue", msg);
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

        // 2. 批量查询用户信息 (发送者 + 被回复者)
        Set<Long> userIds = new HashSet<>();
        for (Comment c : allComments) {
            userIds.add(c.getUserId());         // 发送者
            if (c.getTargetUserId() != null) {
                userIds.add(c.getTargetUserId()); // 被回复的人
            }
        }

        Map<Long, User> userMap = userService.getUserMapCached(userIds);

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

        // 4. 构建树形结构 (根评论 -> 子评论列表)
        List<Comment> rootComments = allComments.stream()
                .filter(c -> c.getParentId() == 0)
                .collect(Collectors.toList());

        Map<Long, List<Comment>> childrenMap = allComments.stream()
                .filter(c -> c.getParentId() != 0)
                .collect(Collectors.groupingBy(Comment::getParentId));

        for (Comment root : rootComments) {
            List<Comment> children = childrenMap.get(root.getId());
            if (children == null) {
                children = new ArrayList<>();
            }
            root.setChildren(children);
        }

        return rootComments;
    }

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
            throw new RuntimeException("关联文章不存在"); // 严谨一点
        }

        // 3. 权限校验 (与原逻辑保持一致：自己删自己的 OR 文章作者删评论)
        boolean isSelf = comment.getUserId().equals(userId);
        boolean isAuthor = article.getAuthorId().equals(userId);

        if (!isSelf && !isAuthor) {
            throw new RuntimeException("无权删除该评论");
        }

        // 4. 收集需要删除的评论ID
        List<Long> deleteIds = new ArrayList<>();
        deleteIds.add(commentId);

        // 5. 判断是否是根评论 (级联删除逻辑)
        if (comment.getParentId() == null || comment.getParentId() == 0) {
            // 查出所有子评论
            List<Comment> children = list(new QueryWrapper<Comment>().eq("parent_id", commentId));
            if (children != null && !children.isEmpty()) {
                List<Long> childIds = children.stream().map(Comment::getId).collect(Collectors.toList());
                deleteIds.addAll(childIds);
            }
        }

        // 6. 批量逻辑删除 (DB操作)
        // 这里的 removeBatchByIds 会把 ID 列表里的所有评论 is_deleted 设为 1
        if (!deleteIds.isEmpty()) {
            removeBatchByIds(deleteIds);
        }

        // 7. 【MQ 异步更新文章评论数】
        int deleteCount = deleteIds.size(); // 计算总共删了多少条
        if (deleteCount > 0) {
            CommentTaskDTO task = new CommentTaskDTO();
            task.setArticleId(comment.getArticleId());
            task.setAdd(false); // 减少
            task.setCount(deleteCount); // 【关键】告诉 MQ 减去多少

            rabbitTemplate.convertAndSend("comment.task.queue", task);
        }
    }
}
