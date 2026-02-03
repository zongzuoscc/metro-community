package cumt.zongzuo.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cumt.zongzuo.community.entity.Follow;
import cumt.zongzuo.community.mapper.FollowMapper;
import cumt.zongzuo.community.service.FollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements FollowService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void follow(Long followerId, Long followedId) {
        // 不能关注自己
        if (followerId.equals(followedId)) {
            throw new RuntimeException("不能关注自己");
        }

        // Redis Key: user:following:1001 (存 1001 关注了哪些人)
        String key = "user:following:" + followerId;

        // 1. 判断是否已关注
        Boolean isMember = redisTemplate.opsForSet().isMember(key, followedId.toString());

        if (Boolean.TRUE.equals(isMember)) {
            // --- 取消关注 ---
            QueryWrapper<Follow> wrapper = new QueryWrapper<>();
            wrapper.eq("follower_id", followerId).eq("followed_id", followedId);
            remove(wrapper);

            redisTemplate.opsForSet().remove(key, followedId.toString());
        } else {
            // --- 关注 ---
            Follow follow = new Follow();
            follow.setFollowerId(followerId);
            follow.setFollowedId(followedId);
            follow.setCreateTime(LocalDateTime.now());

            try {
                save(follow);
            } catch (Exception e) {
                // 忽略唯一索引冲突
                return;
            }

            redisTemplate.opsForSet().add(key, followedId.toString());
        }
    }

    @Override
    public boolean isFollowed(Long followerId, Long followedId) {
        String key = "user:following:" + followerId;
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, followedId.toString()));
    }
}