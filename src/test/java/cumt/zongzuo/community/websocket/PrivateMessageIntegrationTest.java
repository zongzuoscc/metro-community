package cumt.zongzuo.community.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PrivateMessageIntegrationTest extends IntegrationTestSupport {

    private static final long SENDER_ID = 42L;
    private static final long RECIPIENT_ID = 43L;
    private static final long NO_CONTACTS_ID = 44L;
    private static final long LEGACY_ROBOT_ID = 9999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int webSocketPort;

    private final List<MessageListener> openConnections = new ArrayList<>();

    @BeforeAll
    void seedUsers() {
        insertUser(SENDER_ID, "private-message-sender", "sender@example.com");
        insertUser(RECIPIENT_ID, "private-message-recipient", "recipient@example.com");
        insertUser(NO_CONTACTS_ID, "private-message-no-contacts", "no-contacts@example.com");
        insertUser(LEGACY_ROBOT_ID, "legacy-ai-robot", "legacy-ai@example.com");
    }

    @BeforeEach
    void seedMutualFollowAndClearMessages() {
        jdbcTemplate.update("DELETE FROM chat_msg WHERE from_id IN (42, 43, 44, 9999) OR to_id IN (42, 43, 44, 9999)");
        jdbcTemplate.update("DELETE FROM follow WHERE follower_id IN (42, 43, 44) OR followed_id IN (42, 43, 44)");
        jdbcTemplate.update("INSERT INTO follow (follower_id, followed_id, create_time) VALUES (?, ?, NOW())",
                SENDER_ID, RECIPIENT_ID);
        jdbcTemplate.update("INSERT INTO follow (follower_id, followed_id, create_time) VALUES (?, ?, NOW())",
                RECIPIENT_ID, SENDER_ID);
    }

    @AfterEach
    void closeConnections() throws Exception {
        for (MessageListener listener : openConnections) {
            listener.closeClient();
        }
        openConnections.clear();
    }

    @Test
    void ordinaryWebSocketMessagePersistsAndReachesRecipientWithAiDisabled() throws Exception {
        MessageListener recipient = connect(issueTicket(RECIPIENT_ID));
        MessageListener sender = connect(issueTicket(SENDER_ID));
        recipient.awaitOpen();
        sender.awaitOpen();

        sender.webSocket().sendText("{\"toId\":43,\"content\":\"hello\"}", true)
                .get(2, TimeUnit.SECONDS);

        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM chat_msg WHERE from_id=42 AND to_id=43 AND content='hello'",
                        Integer.class)).isEqualTo(1));
        JsonNode receivedJson = objectMapper.readTree(recipient.awaitMessage());
        assertThat(receivedJson.path("fromId").asLong()).isEqualTo(42L);
        assertThat(receivedJson.path("content").asText()).isEqualTo("hello");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_msg WHERE from_id=9999 OR to_id=9999", Integer.class)).isZero();
    }

    @Test
    void friendsWithoutContactsDoNotContainLegacyRobotWhenAiIsDisabled() throws Exception {
        String response = mockMvc.perform(get("/api/chat/friends")
                        .header("Authorization", "Bearer " + jwtService.generate(NO_CONTACTS_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode friends = objectMapper.readTree(response).path("data");
        assertThat(friends).isEmpty();
        assertThat(friends).noneMatch(friend -> friend.path("id").asLong() == LEGACY_ROBOT_ID);
    }

    @Test
    void userWithoutMutualFollowStillCannotPersistPrivateMessages() throws Exception {
        mockMvc.perform(post("/api/chat/send")
                        .header("Authorization", "Bearer " + jwtService.generate(NO_CONTACTS_ID))
                        .param("toId", Long.toString(RECIPIENT_ID))
                        .param("content", "blocked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("必须互相关注才能发送私信"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_msg WHERE from_id=44 AND to_id=43 AND content='blocked'",
                Integer.class)).isZero();
    }

    private void insertUser(long id, String username, String email) {
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, email, role, status)
                VALUES (?, ?, ?, ?, 0, 0)
                ON DUPLICATE KEY UPDATE username = VALUES(username), status = VALUES(status), deleted = 0
                """, id, username, "unused", email);
    }

    private String issueTicket(long userId) throws Exception {
        String response = mockMvc.perform(post("/api/ws/ticket")
                        .header("Authorization", "Bearer " + jwtService.generate(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("ticket").asText();
    }

    private MessageListener connect(String ticket) throws Exception {
        MessageListener listener = new MessageListener();
        WebSocket webSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .buildAsync(URI.create("ws://127.0.0.1:" + webSocketPort + "/im/" + ticket), listener)
                .get(3, TimeUnit.SECONDS);
        listener.attach(webSocket);
        openConnections.add(listener);
        return listener;
    }

    private static final class MessageListener implements WebSocket.Listener {
        private final CompletableFuture<Void> opened = new CompletableFuture<>();
        private final CompletableFuture<String> message = new CompletableFuture<>();
        private final StringBuilder messageBuffer = new StringBuilder();
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

        String awaitMessage() throws Exception {
            return message.get(3, TimeUnit.SECONDS);
        }

        void closeClient() throws Exception {
            WebSocket current = webSocket;
            if (current != null && !current.isOutputClosed()) {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.webSocket = webSocket;
            opened.complete(null);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);
            if (last) {
                message.complete(messageBuffer.toString());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            opened.completeExceptionally(error);
            message.completeExceptionally(error);
        }
    }
}
