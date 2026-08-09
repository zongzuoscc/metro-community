package cumt.zongzuo.community.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cumt.zongzuo.community.service.ChatService;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class WebSocketServer {

    private static final CloseReason POLICY_VIOLATION = new CloseReason(
            CloseReason.CloseCodes.VIOLATED_POLICY, "Authentication failed");
    private static final CloseReason SESSION_REPLACED = new CloseReason(
            CloseReason.CloseCodes.NORMAL_CLOSURE, "Replaced by a new connection");

    private final WebSocketTicketService ticketService;
    private final WebSocketSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final ChatService chatService;

    private Long userId;

    public WebSocketServer(WebSocketTicketService ticketService,
                           WebSocketSessionRegistry sessionRegistry,
                           ObjectMapper objectMapper,
                           ChatService chatService) {
        this.ticketService = ticketService;
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
        this.chatService = chatService;
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("ticket") String ticket) {
        Long authenticatedUserId;
        try {
            authenticatedUserId = ticketService.consume(ticket);
        } catch (WebSocketTicketStoreException exception) {
            log.warn("WebSocket authentication unavailable");
            closeQuietly(session, POLICY_VIOLATION);
            return;
        }
        if (authenticatedUserId == null) {
            log.warn("WebSocket authentication rejected");
            closeQuietly(session, POLICY_VIOLATION);
            return;
        }

        this.userId = authenticatedUserId;
        Session previous = sessionRegistry.replace(authenticatedUserId, session);
        log.info("用户上线: {}, 当前在线人数: {}", authenticatedUserId, sessionRegistry.size());
        if (previous != null && previous != session && previous.isOpen()) {
            closeQuietly(previous, SESSION_REPLACED);
        }
    }

    @OnClose
    public void onClose(Session session) {
        if (userId != null && sessionRegistry.remove(userId, session)) {
            log.info("用户下线: {}", userId);
        }
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        if (userId == null) {
            closeQuietly(session, POLICY_VIOLATION);
            return;
        }
        log.debug("收到用户{}的 WebSocket 消息", userId);
        try {
            ObjectNode msgObj = (ObjectNode) objectMapper.readTree(message);
            Long toId = msgObj.get("toId").asLong();
            String content = msgObj.get("content").asText();

            chatService.sendChat(userId, toId, content);
            Session toSession = sessionRegistry.find(toId);
            if (toSession != null && toSession.isOpen()) {
                ObjectNode pushMsg = objectMapper.createObjectNode();
                pushMsg.put("fromId", userId);
                pushMsg.put("content", content);
                pushMsg.put("type", "chat");
                toSession.getAsyncRemote().sendText(pushMsg.toString());
            } else {
                log.info("用户{}不在线", toId);
            }
        } catch (Exception exception) {
            log.warn("WebSocket message processing failed: {}", exception.getClass().getSimpleName());
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        String errorType = error == null ? "Unknown" : error.getClass().getSimpleName();
        log.warn("WebSocket connection error: {}", errorType);
    }

    private void closeQuietly(Session session, CloseReason reason) {
        try {
            if (session.isOpen()) {
                session.close(reason);
            }
        } catch (IOException exception) {
            log.warn("WebSocket close failed: {}", exception.getClass().getSimpleName());
        }
    }
}
