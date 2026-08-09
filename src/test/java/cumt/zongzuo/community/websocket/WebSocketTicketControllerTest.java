package cumt.zongzuo.community.websocket;

import cumt.zongzuo.community.controller.WebSocketTicketController;
import cumt.zongzuo.community.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebSocketTicketControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void redisFailureReturns503WithoutIssuingTicket() throws Exception {
        WebSocketTicketService ticketService = mock(WebSocketTicketService.class);
        when(ticketService.issue(42L)).thenThrow(new WebSocketTicketStoreException("unavailable"));
        WebSocketTicketController controller = new WebSocketTicketController(ticketService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, null));

        mockMvc.perform(post("/api/ws/ticket"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
