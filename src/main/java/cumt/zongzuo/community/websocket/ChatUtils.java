package cumt.zongzuo.community.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cumt.zongzuo.community.entity.ChatMsg;
import cumt.zongzuo.community.service.ChatService;
import cumt.zongzuo.community.service.MetroAiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.websocket.Session;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "spring.ai.openai.chat.enabled", havingValue = "true")
public class ChatUtils {

    @Autowired
    private ChatService chatService;

    // 【新增】注入 AI 服务
    @Autowired
    private MetroAiService metroAiService;

    private static ChatUtils self;

    @PostConstruct
    public void init() {
        self = this;
    }

    /**
     * 异步保存消息 (原有功能)
     */
    public static void saveMessageAsync(Long fromId, Long toId, String content) {
        new Thread(() -> {
            try {
                ChatMsg msg = new ChatMsg();
                msg.setFromId(fromId);
                msg.setToId(toId);
                msg.setContent(content);
                msg.setCreateTime(LocalDateTime.now());
                msg.setStatus(0); // 0-未读
                self.chatService.save(msg);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * 【核心新增】异步请求 AI 并推送结果
     */
    public static void handleAiChatAsync(Long fromUserId, String content, Session userSession, ObjectMapper objectMapper) {
        new Thread(() -> {
            try {
                // 1. 请求 DeepSeek 获取回答 (这步可能耗时1-3秒)
                String aiAnswer = self.metroAiService.chatWithMetro(content);

                // 2. 将 AI 的回复存入数据库 (发送者是 9999，接收者是用户)
                ChatMsg msg = new ChatMsg();
                msg.setFromId(9999L);
                msg.setToId(fromUserId);
                msg.setContent(aiAnswer);
                msg.setCreateTime(LocalDateTime.now());
                msg.setStatus(0);
                self.chatService.save(msg);

                // 3. 通过 WebSocket 实时推送给该用户
                if (userSession != null && userSession.isOpen()) {
                    ObjectNode pushMsg = objectMapper.createObjectNode();
                    pushMsg.put("fromId", 9999L);
                    pushMsg.put("content", aiAnswer);
                    pushMsg.put("type", "chat");
                    userSession.getAsyncRemote().sendText(pushMsg.toString());
                }
            } catch (Exception e) {
                e.printStackTrace();
                // 异常兜底，给用户回复一个提示
                try {
                    if (userSession != null && userSession.isOpen()) {
                        ObjectNode pushMsg = objectMapper.createObjectNode();
                        pushMsg.put("fromId", 9999L);
                        pushMsg.put("content", "糟糕，我的脑机接口好像断开了，请稍后再试...");
                        pushMsg.put("type", "chat");
                        userSession.getAsyncRemote().sendText(pushMsg.toString());
                    }
                } catch (Exception ex) {}
            }
        }).start();
    }
}
