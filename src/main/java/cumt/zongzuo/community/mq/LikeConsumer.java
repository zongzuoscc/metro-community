package cumt.zongzuo.community.mq;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cumt.zongzuo.community.dto.LikeTaskDTO;
import cumt.zongzuo.community.dto.NotificationMsgDTO;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.entity.Comment;
import cumt.zongzuo.community.entity.LikeRecord;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.CommentMapper;
import cumt.zongzuo.community.mapper.LikeRecordMapper;
import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventType;
import cumt.zongzuo.community.recommendation.service.RecommendationEventOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RabbitListener(queues = "like.task.queue")
public class LikeConsumer {

    @Autowired
    private LikeRecordMapper likeRecordMapper;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RecommendationEventOutboxService recommendationEventOutboxService;

    @RabbitHandler
    @Transactional
    public void handle(LikeTaskDTO msg) {
        Long userId = msg.getUserId();
        Long targetId = msg.getTargetId();
        Integer targetType = msg.getTargetType();

        try {
            if (msg.isLike()) {
                // ============ 处理点赞 ============

                // 1. 插入记录 (捕获唯一索引冲突，防止重复插入)
                LikeRecord record = new LikeRecord();
                record.setUserId(userId);
                record.setTargetId(targetId);
                record.setTargetType(targetType);
                record.setCreateTime(LocalDateTime.now());
                try {
                    likeRecordMapper.insert(record);
                } catch (DuplicateKeyException e) {
                    // A redelivered message whose database transaction already completed.
                    return;
                }
                if (targetType == 1) {
                    recommendationEventOutboxService.enqueue(new RecommendationEventCommand(
                            userId,
                            targetId,
                            null,
                            RecommendationEventType.LIKE,
                            record.getCreateTime(),
                            "like:" + record.getId(),
                            "article_detail"));
                }

                // 2. 更新计数 + 发送通知
                Long receiverId = null;

                if (targetType == 1) { // 文章
                    Article article = articleMapper.selectById(targetId);
                    if (article != null) {
                        article.setLikeCount((article.getLikeCount() == null ? 0 : article.getLikeCount()) + 1);
                        articleMapper.updateById(article);
                        receiverId = article.getAuthorId();
                    }
                } else if (targetType == 2) { // 评论
                    Comment comment = commentMapper.selectById(targetId);
                    if (comment != null) {
                        comment.setLikeCount((comment.getLikeCount() == null ? 0 : comment.getLikeCount()) + 1);
                        commentMapper.updateById(comment);
                        receiverId = comment.getUserId();
                    }
                }

                // 3. 发送通知消息 (继续扔给 notificationQueue)
                if (receiverId != null) {
                    NotificationMsgDTO notify = new NotificationMsgDTO();
                    notify.setFromId(userId);
                    notify.setToId(receiverId);
                    notify.setType(1); // 点赞类型
                    notify.setTargetId(targetId);
                    // 异步发送通知
                    rabbitTemplate.convertAndSend("message.notify.queue", notify);
                }

            } else {
                // ============ 处理取消点赞 ============

                QueryWrapper<LikeRecord> wrapper = new QueryWrapper<>();
                wrapper.eq("user_id", userId)
                        .eq("target_id", targetId)
                        .eq("target_type", targetType);
                int deleted = likeRecordMapper.delete(wrapper);

                // 只有真正删除了记录，才去减计数
                if (deleted > 0) {
                    if (targetType == 1) {
                        Article article = articleMapper.selectById(targetId);
                        if (article != null && article.getLikeCount() > 0) {
                            article.setLikeCount(article.getLikeCount() - 1);
                            articleMapper.updateById(article);
                        }
                    } else if (targetType == 2) {
                        Comment comment = commentMapper.selectById(targetId);
                        if (comment != null && comment.getLikeCount() > 0) {
                            comment.setLikeCount(comment.getLikeCount() - 1);
                            commentMapper.updateById(comment);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("点赞异步处理失败: {}", msg, e);
            throw new IllegalStateException("点赞任务执行失败", e);
        }
    }
}
