package cumt.zongzuo.community.websocket;

import jakarta.websocket.Session;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class WebSocketSessionRegistry {

    private final ConcurrentMap<Long, Session> sessions = new ConcurrentHashMap<>();

    public Session replace(Long userId, Session session) {
        return sessions.put(userId, session);
    }

    public boolean remove(Long userId, Session session) {
        return sessions.remove(userId, session);
    }

    public Session find(Long userId) {
        return sessions.get(userId);
    }

    public int size() {
        return sessions.size();
    }
}
