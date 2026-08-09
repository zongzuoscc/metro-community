package cumt.zongzuo.community.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.service.ChatService;
import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketServerTest {

    @Test
    void redisFailureClosesConnectionWithoutRegisteringSession() throws IOException {
        WebSocketTicketService ticketService = mock(WebSocketTicketService.class);
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
        Session session = mock(Session.class);
        ChatService chatService = mock(ChatService.class);
        when(session.isOpen()).thenReturn(true);
        when(ticketService.consume("a".repeat(43)))
                .thenThrow(new WebSocketTicketStoreException("unavailable"));
        WebSocketServer endpoint = new WebSocketServer(ticketService, registry, new ObjectMapper(), chatService);

        endpoint.onOpen(session, "a".repeat(43));

        verify(session).close(any(CloseReason.class));
        assertThat(registry.find(42L)).isNull();
    }

    @Test
    void oldEndpointCloseCannotRemoveReplacementSession() throws IOException {
        WebSocketTicketService ticketService = mock(WebSocketTicketService.class);
        when(ticketService.consume("a".repeat(43))).thenReturn(42L);
        when(ticketService.consume("b".repeat(43))).thenReturn(42L);
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
        Session oldSession = mock(Session.class);
        Session replacement = mock(Session.class);
        ChatService chatService = mock(ChatService.class);
        when(oldSession.isOpen()).thenReturn(true);
        when(replacement.isOpen()).thenReturn(true);
        WebSocketServer oldEndpoint = new WebSocketServer(ticketService, registry, new ObjectMapper(), chatService);
        WebSocketServer replacementEndpoint = new WebSocketServer(ticketService, registry, new ObjectMapper(), chatService);

        oldEndpoint.onOpen(oldSession, "a".repeat(43));
        replacementEndpoint.onOpen(replacement, "b".repeat(43));
        oldEndpoint.onClose(oldSession);

        verify(oldSession).close(any(CloseReason.class));
        verify(replacement, never()).close(any(CloseReason.class));
        assertThat(registry.find(42L)).isSameAs(replacement);
    }

    @Test
    void normalFrameIsPersistedBeforeItIsPushed() {
        WebSocketTicketService ticketService = mock(WebSocketTicketService.class);
        when(ticketService.consume("a".repeat(43))).thenReturn(42L);
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
        Session sender = mock(Session.class);
        Session recipient = mock(Session.class);
        RemoteEndpoint.Async recipientRemote = mock(RemoteEndpoint.Async.class);
        ChatService chatService = mock(ChatService.class);
        when(sender.isOpen()).thenReturn(true);
        when(recipient.isOpen()).thenReturn(true);
        when(recipient.getAsyncRemote()).thenReturn(recipientRemote);
        registry.replace(43L, recipient);
        WebSocketServer endpoint = new WebSocketServer(ticketService, registry, new ObjectMapper(), chatService);
        endpoint.onOpen(sender, "a".repeat(43));

        endpoint.onMessage("{\"toId\":43,\"content\":\"hello\"}", sender);

        var ordered = inOrder(chatService, recipientRemote);
        ordered.verify(chatService).sendChat(42L, 43L, "hello");
        ordered.verify(recipientRemote).sendText("{\"fromId\":42,\"content\":\"hello\",\"type\":\"chat\"}");
    }

    @Test
    void persistenceFailureIsNotPushedAsDelivered() {
        WebSocketTicketService ticketService = mock(WebSocketTicketService.class);
        when(ticketService.consume("a".repeat(43))).thenReturn(42L);
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
        Session sender = mock(Session.class);
        Session recipient = mock(Session.class);
        RemoteEndpoint.Async recipientRemote = mock(RemoteEndpoint.Async.class);
        ChatService chatService = mock(ChatService.class);
        when(sender.isOpen()).thenReturn(true);
        when(recipient.isOpen()).thenReturn(true);
        when(recipient.getAsyncRemote()).thenReturn(recipientRemote);
        doThrow(new RuntimeException("must follow each other"))
                .when(chatService).sendChat(42L, 43L, "hello");
        registry.replace(43L, recipient);
        WebSocketServer endpoint = new WebSocketServer(ticketService, registry, new ObjectMapper(), chatService);
        endpoint.onOpen(sender, "a".repeat(43));

        endpoint.onMessage("{\"toId\":43,\"content\":\"hello\"}", sender);

        verify(recipientRemote, never()).sendText(any());
    }
}
