package cumt.zongzuo.community.websocket;

import cumt.zongzuo.community.entity.ChatMsg;
import cumt.zongzuo.community.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// 【关键修改】Spring Boot 3 使用 jakarta.annotation
import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;

@Component
public class ChatUtils {

    @Autowired
    private ChatService chatService;

    private static ChatUtils self;

    @PostConstruct
    public void init() {
        self = this;
    }

    /**
     * 异步保存消息
     */
    public static void saveMessageAsync(Long fromId, Long toId, String content) {
        // 使用新线程异步执行，避免阻塞 WebSocket 线程
        new Thread(() -> {
            try {
                ChatMsg msg = new ChatMsg();
                msg.setFromId(fromId);
                msg.setToId(toId);
                msg.setContent(content);
                msg.setCreateTime(LocalDateTime.now());
                msg.setStatus(0); // 0-未读

                // 使用 self 静态实例调用注入的 service
                self.chatService.save(msg);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}