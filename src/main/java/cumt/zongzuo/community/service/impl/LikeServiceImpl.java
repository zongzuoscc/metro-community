package cumt.zongzuo.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import cumt.zongzuo.community.dto.LikeTaskDTO;
import cumt.zongzuo.community.dto.NotificationMsgDTO;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.entity.Comment; // 引入 Comment
import cumt.zongzuo.community.entity.LikeRecord;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.CommentMapper; // 引入 Mapper
import cumt.zongzuo.community.mapper.LikeRecordMapper;
import cumt.zongzuo.community.service.LikeService;
import cumt.zongzuo.community.service.MessageService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LikeServiceImpl extends ServiceImpl<LikeRecordMapper, LikeRecord> implements LikeService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private CommentMapper commentMapper; // 【新增】注入 CommentMapper

    @Autowired
    private MessageService messageService;

    @Override
    public void like(Long userId, Long targetId, Integer targetType) {
        String key = getRedisKey(targetId, targetType);

        // 1. 操作 Redis (即时反馈)
        boolean isLike;
        if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, userId.toString()))) {
            // 已点赞 -> 取消
            redisTemplate.opsForSet().remove(key, userId.toString());
            isLike = false;
        } else {
            // 未点赞 -> 点赞
            redisTemplate.opsForSet().add(key, userId.toString());
            isLike = true;
        }

        // 2. 发送异步消息 (削峰填谷)
        LikeTaskDTO task = new LikeTaskDTO();
        task.setUserId(userId);
        task.setTargetId(targetId);
        task.setTargetType(targetType);
        task.setLike(isLike);

        rabbitTemplate.convertAndSend("like.task.queue", task);

        // 方法直接结束，无需等待数据库操作完成
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
