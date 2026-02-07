package cumt.zongzuo.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cumt.zongzuo.community.entity.Follow;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.mapper.FollowMapper;
import cumt.zongzuo.community.mapper.UserMapper;
import cumt.zongzuo.community.service.FollowService;
import cumt.zongzuo.community.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements FollowService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private MessageService messageService;

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
                // 【新增】发送通知 (type=3 关注)
                // targetId 此时可以是 followerId，点击跳转到关注者主页
                messageService.send(followerId, followedId, 3, followerId, null);
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

    @Override
    public Page<User> getUserFollowings(Long userId, int pageNo, int pageSize) {
        // 1. 先查 Follow 表 (分页)
        Page<Follow> followPage = new Page<>(pageNo, pageSize);
        QueryWrapper<Follow> wrapper = new QueryWrapper<>();
        wrapper.eq("follower_id", userId);
        wrapper.orderByDesc("create_time");

        Page<Follow> result = page(followPage, wrapper);

        // 2. 如果没数据，直接返回空 Page
        if (result.getRecords().isEmpty()) {
            return new Page<>(pageNo, pageSize);
        }

        // 3. 提取被关注人 ID
        Set<Long> userIds = result.getRecords().stream()
                .map(Follow::getFollowedId)
                .collect(Collectors.toSet());

        // 4. 批量查 User
        List<User> users = userMapper.selectBatchIds(userIds);
        users.forEach(u -> u.setPassword(null));

        // 5. 将 User 列表封装回 Page 对象
        // 注意：这里简单的做法是直接把 List<Follow> 替换成 List<User>
        // 但 Page 的泛型变了，所以我们需要新建一个 Page<User>
        Page<User> userPage = new Page<>(pageNo, pageSize, result.getTotal());
        userPage.setRecords(users);

        return userPage;
    }

    @Override
    public Page<User> getUserFans(Long userId, int pageNo, int pageSize) {
        // 1. 先查 Follow 表 (分页)
        Page<Follow> followPage = new Page<>(pageNo, pageSize);
        QueryWrapper<Follow> wrapper = new QueryWrapper<>();
        wrapper.eq("followed_id", userId); // 查谁关注了我
        wrapper.orderByDesc("create_time");

        Page<Follow> result = page(followPage, wrapper);

        if (result.getRecords().isEmpty()) {
            return new Page<>(pageNo, pageSize);
        }

        // 2. 提取粉丝 ID (follower_id)
        Set<Long> userIds = result.getRecords().stream()
                .map(Follow::getFollowerId)
                .collect(Collectors.toSet());

        // 3. 批量查 User
        List<User> users = userMapper.selectBatchIds(userIds);
        users.forEach(u -> u.setPassword(null));

        // 4. 封装返回
        Page<User> userPage = new Page<>(pageNo, pageSize, result.getTotal());
        userPage.setRecords(users);

        return userPage;
    }
}