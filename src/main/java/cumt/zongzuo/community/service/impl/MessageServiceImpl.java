package cumt.zongzuo.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cumt.zongzuo.community.entity.Message;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.mapper.MessageMapper;
import cumt.zongzuo.community.mapper.UserMapper;
import cumt.zongzuo.community.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message> implements MessageService {

    @Autowired
    private UserMapper userMapper; // 用于填充发送者信息

    @Override
    public void send(Long fromId, Long toId, Integer type, Long targetId, String content) {
        // 自己给自己点的赞/评的论，不发通知
        if (fromId.equals(toId)) {
            return;
        }

        Message message = new Message();
        message.setFromId(fromId);
        message.setToId(toId);
        message.setType(type);
        message.setTargetId(targetId);
        message.setContent(content);
        message.setStatus(0); // 默认为未读
        message.setCreateTime(LocalDateTime.now());

        save(message);
    }

    @Override
    public Page<Message> getMyMessages(Long userId, int pageNo, int size) {
        // 1. 分页查询
        Page<Message> page = new Page<>(pageNo, size);
        QueryWrapper<Message> wrapper = new QueryWrapper<>();
        wrapper.eq("to_id", userId);
        wrapper.orderByDesc("create_time"); // 最新消息在最前

        Page<Message> result = page(page, wrapper);
        List<Message> list = result.getRecords();

        if (list.isEmpty()) return result;

        // 2. 批量填充发送者信息 (fromId -> User)
        Set<Long> userIds = list.stream().map(Message::getFromId).collect(Collectors.toSet());
        List<User> users = userMapper.selectBatchIds(userIds);
        Map<Long, User> userMap = users.stream().collect(Collectors.toMap(User::getId, u -> u));

        for (Message msg : list) {
            User u = userMap.get(msg.getFromId());
            if (u != null) {
                msg.setFromUsername(u.getUsername());
                msg.setFromAvatar(u.getAvatar());
            } else {
                msg.setFromUsername("未知用户");
            }
        }

        return result;
    }

    @Override
    public Long getUnreadCount(Long userId) {
        return baseMapper.selectUnreadCount(userId);
    }

    @Override
    public void readAll(Long userId) {
        baseMapper.readAll(userId);
    }
}