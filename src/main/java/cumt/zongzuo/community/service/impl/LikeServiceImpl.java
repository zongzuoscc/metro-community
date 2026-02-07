package cumt.zongzuo.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.entity.Comment; // 引入 Comment
import cumt.zongzuo.community.entity.LikeRecord;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.CommentMapper; // 引入 Mapper
import cumt.zongzuo.community.mapper.LikeRecordMapper;
import cumt.zongzuo.community.service.LikeService;
import cumt.zongzuo.community.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class LikeServiceImpl extends ServiceImpl<LikeRecordMapper, LikeRecord> implements LikeService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private CommentMapper commentMapper; // 【新增】注入 CommentMapper

    @Autowired
    private MessageService messageService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void like(Long userId, Long targetId, Integer targetType) {
        String key = getRedisKey(targetId, targetType);

        // 1. 判断是否已点赞
        Boolean isMember = redisTemplate.opsForSet().isMember(key, userId.toString());

        if (Boolean.TRUE.equals(isMember)) {
            // --- 取消点赞 ---
            QueryWrapper<LikeRecord> wrapper = new QueryWrapper<>();
            wrapper.eq("user_id", userId)
                    .eq("target_id", targetId)
                    .eq("target_type", targetType);
            remove(wrapper);

            // 1.2.1 如果是文章
            if (targetType == 1) {
                Article article = articleMapper.selectById(targetId);
                if (article != null && article.getLikeCount() > 0) {
                    article.setLikeCount(article.getLikeCount() - 1);
                    articleMapper.updateById(article);
                }
            }
            // 1.2.2 【新增】如果是评论，减少点赞数
            else if (targetType == 2) {
                Comment comment = commentMapper.selectById(targetId);
                if (comment != null && comment.getLikeCount() > 0) {
                    comment.setLikeCount(comment.getLikeCount() - 1);
                    commentMapper.updateById(comment);
                }
            }

            redisTemplate.opsForSet().remove(key, userId.toString());

        } else {
            // --- 点赞 ---
            LikeRecord record = new LikeRecord();
            record.setUserId(userId);
            record.setTargetId(targetId);
            record.setTargetType(targetType);
            record.setCreateTime(LocalDateTime.now());
            try {
                save(record);
            } catch (Exception e) {
                return;
            }

            // 2.2.1 如果是文章
            if (targetType == 1) {
                Article article = articleMapper.selectById(targetId);
                if (article != null) {
                    article.setLikeCount(article.getLikeCount() + 1);
                    articleMapper.updateById(article);
                }
            }
            // 2.2.2 【新增】如果是评论，增加点赞数
            else if (targetType == 2) {
                Comment comment = commentMapper.selectById(targetId);
                if (comment != null) {
                    comment.setLikeCount(comment.getLikeCount() + 1);
                    commentMapper.updateById(comment);
                }
            }

            redisTemplate.opsForSet().add(key, userId.toString());
            // 【新增】发送通知
            // 我们需要知道这篇文章/评论是谁写的
            Long receiverId = null;
            if (targetType == 1) { // 文章
                Article article = articleMapper.selectById(targetId);
                if(article != null) receiverId = article.getAuthorId();
            } else if (targetType == 2) { // 评论
                Comment comment = commentMapper.selectById(targetId);
                if(comment != null) receiverId = comment.getUserId();
            }

            if (receiverId != null) {
                // type=1 代表点赞
                messageService.send(userId, receiverId, 1, targetId, null);
            }
        }
    }

    // ... 其他方法保持不变 ...
    @Override
    public boolean isLiked(Long userId, Long targetId, Integer targetType) {
        String key = getRedisKey(targetId, targetType);
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, userId.toString()));
    }

    private String getRedisKey(Long targetId, Integer targetType) {
        String typeStr = (targetType == 1) ? "article" : "comment";
        return "like:" + typeStr + ":" + targetId;
    }
}