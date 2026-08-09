package cumt.zongzuo.community.websocket;

import cumt.zongzuo.community.config.WebSocketProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class WebSocketTicketService {

    static final String KEY_PREFIX = "websocket:ticket:";
    private static final int RANDOM_BYTES = 32;
    private static final int MAX_GENERATION_ATTEMPTS = 3;
    private static final Pattern TICKET_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final DefaultRedisScript<String> CONSUME_TICKET_SCRIPT = new DefaultRedisScript<>("""
            local userId = redis.call('GET', KEYS[1])
            if not userId then
                return nil
            end
            redis.call('DEL', KEYS[1])
            return userId
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration ticketTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public WebSocketTicketService(StringRedisTemplate redisTemplate, WebSocketProperties properties) {
        this.redisTemplate = redisTemplate;
        this.ticketTtl = properties.ticketTtl();
    }

    public IssuedTicket issue(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        try {
            for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
                String ticket = generateTicket();
                Boolean stored = redisTemplate.opsForValue()
                        .setIfAbsent(KEY_PREFIX + ticket, userId.toString(), ticketTtl);
                if (Boolean.TRUE.equals(stored)) {
                    return new IssuedTicket(ticket, ticketTtl.toSeconds());
                }
            }
            throw new WebSocketTicketStoreException("Unable to allocate a unique WebSocket ticket");
        } catch (WebSocketTicketStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new WebSocketTicketStoreException("WebSocket ticket store is unavailable", exception);
        }
    }

    public Long consume(String ticket) {
        if (ticket == null || !TICKET_PATTERN.matcher(ticket).matches()) {
            return null;
        }
        try {
            String userId = redisTemplate.execute(CONSUME_TICKET_SCRIPT, List.of(KEY_PREFIX + ticket));
            return userId == null ? null : Long.valueOf(userId);
        } catch (RuntimeException exception) {
            throw new WebSocketTicketStoreException("WebSocket ticket store is unavailable", exception);
        }
    }

    private String generateTicket() {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record IssuedTicket(String ticket, long expiresInSeconds) {
    }
}
