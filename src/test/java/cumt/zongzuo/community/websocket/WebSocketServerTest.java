package cumt.zongzuo.community.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        when(session.isOpen()).thenReturn(true);
        when(ticketService.consume("a".repeat(43)))
                .thenThrow(new WebSocketTicketStoreException("unavailable"));
        WebSocketServer endpoint = new WebSocketServer(ticketService, registry, new ObjectMapper());

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
        when(oldSession.isOpen()).thenReturn(true);
        when(replacement.isOpen()).thenReturn(true);
        WebSocketServer oldEndpoint = new WebSocketServer(ticketService, registry, new ObjectMapper());
        WebSocketServer replacementEndpoint = new WebSocketServer(ticketService, registry, new ObjectMapper());

        oldEndpoint.onOpen(oldSession, "a".repeat(43));
        replacementEndpoint.onOpen(replacement, "b".repeat(43));
        oldEndpoint.onClose(oldSession);

        verify(oldSession).close(any(CloseReason.class));
        verify(replacement, never()).close(any(CloseReason.class));
        assertThat(registry.find(42L)).isSameAs(replacement);
    }
}
