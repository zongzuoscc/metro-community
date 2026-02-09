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
import java.util.*;
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
        // 1. 获取所有通过话的人 (Send or Receive)
        List<ChatMsg> allMsgs = list(new QueryWrapper<ChatMsg>()
                .and(w -> w.eq("from_id", userId).or().eq("to_id", userId))
                .select("from_id", "to_id"));

        Set<Long> contactIds = new HashSet<>();
        for (ChatMsg msg : allMsgs) {
            contactIds.add(msg.getFromId());
            contactIds.add(msg.getToId());
        }
        contactIds.remove(userId); // 排除自己

        // 2. 获取我的关注列表 (为了拿备注)
        List<Follow> myFollowings = followMapper.selectList(new QueryWrapper<Follow>().eq("follower_id", userId));
        Map<Long, Follow> followingMap = myFollowings.stream()
                .collect(Collectors.toMap(Follow::getFollowedId, f -> f));

        // 3. 获取我的粉丝列表 (为了判断互关)
        List<Follow> myFans = followMapper.selectList(new QueryWrapper<Follow>().eq("followed_id", userId));
        Set<Long> fanIds = myFans.stream().map(Follow::getFollowerId).collect(Collectors.toSet());

        // 4. 将互关好友也加入联系人列表 (即使没聊过天也显示)
        for (Follow f : myFollowings) {
            if (fanIds.contains(f.getFollowedId())) {
                contactIds.add(f.getFollowedId());
            }
        }

        if (contactIds.isEmpty()) return new ArrayList<>();

        // 5. 查询 User 信息并填充
        List<User> users = userMapper.selectBatchIds(contactIds);

        for (User u : users) {
            u.setPassword(null);

            // 判断是否是好友 (互关)
            boolean isFollowing = followingMap.containsKey(u.getId());
            boolean isFan = fanIds.contains(u.getId());
            u.setIsFriend(isFollowing && isFan);

            // 填充备注
            if (isFollowing) {
                Follow f = followingMap.get(u.getId());
                u.setRemark(f.getRemark());
                u.setDescription(f.getDescription());
            }
        }

        return users;
    }

    @Override
    public Long getUnreadCount(Long userId) {
        return baseMapper.selectUnreadCount(userId);
    }
}