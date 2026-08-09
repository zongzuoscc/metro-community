package cumt.zongzuo.community.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WebSocketTicketEndpointIntegrationTest extends IntegrationTestSupport {

    private static final long USER_ID = 81_001L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private WebSocketTicketService ticketService;

    @LocalServerPort
    private int webSocketPort;

    @BeforeAll
    void createUser() {
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, email, role, status)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE status = VALUES(status)
                """, USER_ID, "ws-ticket-user", "unused", "ws-ticket@example.com", 0, 0);
    }

    @BeforeEach
    void clearTickets() {
        Set<String> keys = redisTemplate.keys("websocket:ticket:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void unauthenticatedTicketRequestReturns401() throws Exception {
        mockMvc.perform(post("/api/ws/ticket"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void authenticatedTicketIsOpaqueAndConsumableOnlyOnce() throws Exception {
        IssuedTicket issued = issueTicket();

        assertThat(issued.ticket()).matches("[A-Za-z0-9_-]{43}");
        assertThat(issued.expiresInSeconds()).isEqualTo(30);
        assertThat(ticketService.consume(issued.ticket())).isEqualTo(USER_ID);
        assertThat(ticketService.consume(issued.ticket())).isNull();
    }

    @Test
    void issuedTicketOpensAUsableWebSocketConnection() throws Exception {
        TestWebSocketListener listener = connect(issueTicket().ticket());

        listener.awaitOpen();
        listener.webSocket().sendPing(ByteBuffer.wrap(new byte[]{1})).get(2, TimeUnit.SECONDS);
        listener.awaitPong();
        listener.closeClient();
    }

    @Test
    void replayedTicketIsClosedWithPolicyViolation() throws Exception {
        String ticket = issueTicket().ticket();
        TestWebSocketListener first = connect(ticket);
        first.awaitOpen();
        first.closeClient();

        TestWebSocketListener replay = connect(ticket);

        assertThat(replay.awaitCloseCode()).isEqualTo(1008);
    }

    @Test
    void expiredTicketIsClosedWithPolicyViolation() throws Exception {
        String ticket = issueTicket().ticket();
        String key = "websocket:ticket:" + ticket;
        redisTemplate.expire(key, Duration.ofMillis(25));
        Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> Boolean.FALSE.equals(redisTemplate.hasKey(key)));

        TestWebSocketListener expired = connect(ticket);

        assertThat(expired.awaitCloseCode()).isEqualTo(1008);
    }

    @Test
    void forgedTicketIsClosedWithPolicyViolation() throws Exception {
        TestWebSocketListener forged = connect("a".repeat(43));

        assertThat(forged.awaitCloseCode()).isEqualTo(1008);
    }

    @Test
    void originalJwtIsNotAcceptedAsAWebSocketCredential() throws Exception {
        TestWebSocketListener jwt = connect(jwtService.generate(USER_ID));

        assertThat(jwt.awaitCloseCode()).isEqualTo(1008);
    }

    private IssuedTicket issueTicket() throws Exception {
        String response = mockMvc.perform(post("/api/ws/ticket")
                        .header("Authorization", "Bearer " + jwtService.generate(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return new IssuedTicket(data.path("ticket").asText(), data.path("expiresInSeconds").asLong());
    }

    private TestWebSocketListener connect(String credential) throws Exception {
        TestWebSocketListener listener = new TestWebSocketListener();
        WebSocket webSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .buildAsync(URI.create("ws://127.0.0.1:" + webSocketPort + "/im/" + credential), listener)
                .get(3, TimeUnit.SECONDS);
        listener.attach(webSocket);
        return listener;
    }

    private record IssuedTicket(String ticket, long expiresInSeconds) {
    }

    private static final class TestWebSocketListener implements WebSocket.Listener {
        private final CompletableFuture<Void> opened = new CompletableFuture<>();
        private final CompletableFuture<Void> pong = new CompletableFuture<>();
        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private volatile WebSocket webSocket;

        void attach(WebSocket webSocket) {
            this.webSocket = webSocket;
        }

        WebSocket webSocket() {
            return webSocket;
        }

        void awaitOpen() throws Exception {
            opened.get(2, TimeUnit.SECONDS);
        }

        void awaitPong() throws Exception {
            pong.get(2, TimeUnit.SECONDS);
        }

        int awaitCloseCode() throws Exception {
            return closeCode.get(2, TimeUnit.SECONDS);
        }

        void closeClient() throws Exception {
            WebSocket current = webSocket;
            if (current != null && !current.isOutputClosed()) {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
                closeCode.get(2, TimeUnit.SECONDS);
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.webSocket = webSocket;
            opened.complete(null);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            pong.complete(null);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            opened.completeExceptionally(error);
            pong.completeExceptionally(error);
            closeCode.completeExceptionally(error);
        }
    }
}
