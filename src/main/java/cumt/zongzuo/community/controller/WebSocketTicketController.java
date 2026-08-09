package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.WebSocketTicketResponse;
import cumt.zongzuo.community.security.CurrentUser;
import cumt.zongzuo.community.websocket.WebSocketTicketService;
import cumt.zongzuo.community.websocket.WebSocketTicketStoreException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ws")
public class WebSocketTicketController {

    private final WebSocketTicketService ticketService;

    public WebSocketTicketController(WebSocketTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping("/ticket")
    public Result<WebSocketTicketResponse> issueTicket() {
        try {
            WebSocketTicketService.IssuedTicket issued = ticketService.issue(CurrentUser.id());
            return Result.success(new WebSocketTicketResponse(issued.ticket(), issued.expiresInSeconds()));
        } catch (WebSocketTicketStoreException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "实时连接暂不可用");
        }
    }
}
