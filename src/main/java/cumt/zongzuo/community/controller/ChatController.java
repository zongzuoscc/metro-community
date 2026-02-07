package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.entity.ChatMsg;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.service.ChatService;
import cumt.zongzuo.community.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;

    // 1. 发送私信 (HTTP接口，用于非WebSocket环境或兜底)
    @PostMapping("/send")
    public Result<String> send(@RequestParam Long toId, @RequestParam String content, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        try {
            chatService.sendChat(userId, toId, content);
            return Result.success("发送成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    // 2. 获取好友列表 (即会话列表)
    @GetMapping("/friends")
    public Result<List<User>> getFriends(@RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        List<User> list = chatService.getMyFriends(userId);
        return Result.success(list);
    }

    // 3. 获取聊天记录
    @GetMapping("/history")
    public Result<List<ChatMsg>> getHistory(@RequestParam Long friendId, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        List<ChatMsg> list = chatService.getChatHistory(userId, friendId);
        return Result.success(list);
    }

    // 4. 获取未读数 (给信封图标用)
    @GetMapping("/unread")
    public Result<Long> getUnread(@RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        Long count = chatService.getUnreadCount(userId);
        return Result.success(count);
    }
}