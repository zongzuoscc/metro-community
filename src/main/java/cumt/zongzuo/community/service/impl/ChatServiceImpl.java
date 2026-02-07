package cumt.zongzuo.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cumt.zongzuo.community.entity.ChatMsg;
import cumt.zongzuo.community.entity.Follow;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.mapper.ChatMsgMapper;
import cumt.zongzuo.community.mapper.FollowMapper;
import cumt.zongzuo.community.mapper.UserMapper;
import cumt.zongzuo.community.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChatServiceImpl extends ServiceImpl<ChatMsgMapper, ChatMsg> implements ChatService {

    @Autowired
    private FollowMapper followMapper;

    @Autowired
    private UserMapper userMapper;

    @Override
    public void sendChat(Long fromId, Long toId, String content) {
        // 1. 检查是否互相关注 (好友关系)
        // A关注B ?
        Long count1 = followMapper.selectCount(new QueryWrapper<Follow>()
                .eq("follower_id", fromId).eq("followed_id", toId));
        // B关注A ?
        Long count2 = followMapper.selectCount(new QueryWrapper<Follow>()
                .eq("follower_id", toId).eq("followed_id", fromId));

        if (count1 == 0 || count2 == 0) {
            throw new RuntimeException("必须互相关注才能发送私信");
        }

        // 2. 存入数据库 (持久化)
        ChatMsg msg = new ChatMsg();
        msg.setFromId(fromId);
        msg.setToId(toId);
        msg.setContent(content);
        msg.setCreateTime(LocalDateTime.now());
        msg.setStatus(0); // 未读
        save(msg);
    }

    @Override
    public List<ChatMsg> getChatHistory(Long userId, Long friendId) {
        // 查询 (from=我 and to=他) OR (from=他 and to=我)
        QueryWrapper<ChatMsg> wrapper = new QueryWrapper<>();
        wrapper.and(w -> w
                .eq("from_id", userId).eq("to_id", friendId)
                .or()
                .eq("from_id", friendId).eq("to_id", userId)
        );
        wrapper.orderByAsc("create_time"); // 按时间正序

        List<ChatMsg> list = list(wrapper);

        // 顺便把发给我的消息标为已读
        for (ChatMsg msg : list) {
            if (msg.getToId().equals(userId) && msg.getStatus() == 0) {
                msg.setStatus(1);
                updateById(msg);
            }
        }
        return list;
    }

    @Override
    public List<User> getMyFriends(Long userId) {
        // 1. 查我关注的人
        List<Follow> followings = followMapper.selectList(new QueryWrapper<Follow>().eq("follower_id", userId));
        Set<Long> followingIds = followings.stream().map(Follow::getFollowedId).collect(Collectors.toSet());

        if (followingIds.isEmpty()) return new ArrayList<>();

        // 2. 查关注我的人 (粉丝)
        List<Follow> fans = followMapper.selectList(new QueryWrapper<Follow>().eq("followed_id", userId));
        Set<Long> fanIds = fans.stream().map(Follow::getFollowerId).collect(Collectors.toSet());

        // 3. 取交集 (既关注我，我也关注他 -> 互关好友)
        followingIds.retainAll(fanIds);

        if (followingIds.isEmpty()) return new ArrayList<>();

        // 4. 查用户信息
        List<User> friends = userMapper.selectBatchIds(followingIds);
        friends.forEach(u -> u.setPassword(null));
        return friends;
    }

    @Override
    public Long getUnreadCount(Long userId) {
        return baseMapper.selectUnreadCount(userId);
    }
}