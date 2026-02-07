package cumt.zongzuo.community.websocket;

import com.fasterxml.jackson.databind.ObjectMapper; // 使用 Jackson
import com.fasterxml.jackson.databind.node.ObjectNode;
import cumt.zongzuo.community.utils.JwtUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.websocket.*; // Spring Boot 3 必须用 jakarta
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 服务端
 * 访问地址: ws://localhost:8080/im/{token}
 */
@ServerEndpoint("/im/{token}")
@Component
@Slf4j
public class WebSocketServer {

    // 静态变量，用来记录当前在线连接数
    private static ConcurrentHashMap<Long, Session> sessionMap = new ConcurrentHashMap<>();

    // Jackson 的 ObjectMapper，用于 JSON 解析
    private static ObjectMapper objectMapper = new ObjectMapper();

    private Long userId;

    /**
     * 连接建立成功调用的方法
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("token") String token) {
        try {
            // 1. 解析 Token 获取 userId
            this.userId = JwtUtils.getUserId(token);

            if (this.userId != null) {
                sessionMap.put(this.userId, session);
                log.info("用户上线: {}, 当前在线人数: {}", this.userId, sessionMap.size());
            } else {
                session.close();
            }
        } catch (Exception e) {
            log.error("WebSocket认证失败", e);
            try {
                session.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    /**
     * 连接关闭调用的方法
     */
    @OnClose
    public void onClose() {
        if (this.userId != null) {
            sessionMap.remove(this.userId);
            log.info("用户下线: {}", this.userId);
        }
    }

    /**
     * 收到客户端消息后调用的方法
     * 格式: { "toId": 2, "content": "你好" }
     */
    @OnMessage
    public void onMessage(String message, Session session) {
        log.info("收到用户{}的消息: {}", this.userId, message);
        try {
            // 使用 Jackson 解析 JSON
            ObjectNode msgObj = (ObjectNode) objectMapper.readTree(message);
            Long toId = msgObj.get("toId").asLong();
            String content = msgObj.get("content").asText();

            // 1. 发送给接收者 (如果在线)
            Session toSession = sessionMap.get(toId);
            if (toSession != null && toSession.isOpen()) {
                // 构造推送数据
                ObjectNode pushMsg = objectMapper.createObjectNode();
                pushMsg.put("fromId", this.userId);
                pushMsg.put("content", content);
                pushMsg.put("type", "chat");

                // 异步发送
                toSession.getAsyncRemote().sendText(pushMsg.toString());
            } else {
                log.info("用户{}不在线", toId);
            }

            // 2. 异步存库
            ChatUtils.saveMessageAsync(this.userId, toId, content);

        } catch (Exception e) {
            log.error("消息处理异常", e);
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("WebSocket发生错误", error);
    }
}