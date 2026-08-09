package cumt.zongzuo.community.service;

import com.baomidou.mybatisplus.spring.service.IService;
import cumt.zongzuo.community.entity.ChatMsg;
import cumt.zongzuo.community.entity.User;

import java.util.List;

public interface ChatService extends IService<ChatMsg> {

    // 发送私信
    void sendChat(Long fromId, Long toId, String content);

    // 获取我和某人的聊天记录
    List<ChatMsg> getChatHistory(Long userId, Long friendId);

    // 获取我的互关好友列表 (作为聊天列表)
    List<User> getMyFriends(Long userId);

    // 获取私信未读数
    Long getUnreadCount(Long userId);
}