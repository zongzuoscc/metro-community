package cumt.zongzuo.community.ai.agent.web;

import cumt.zongzuo.community.ai.agent.temporary.TemporarySessionService;
import cumt.zongzuo.community.ai.agent.temporary.TemporarySessionStore;
import cumt.zongzuo.community.ai.agent.temporary.TemporarySessionView;
import cumt.zongzuo.community.ai.web.AiApi;
import cumt.zongzuo.community.ai.web.AiApiException;
import cumt.zongzuo.community.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户唯一临时会话的 HTTP 生命周期入口。
 * 接口只返回 sessionId 和时间边界，不暴露 Redis key、消息内容或其他用户的存在性。
 */
@AiApi
@RestController
@RequestMapping("/api/agent/temporary-sessions")
public class TemporarySessionController {

    private final TemporarySessionStore sessions;
    private final TemporarySessionService service;

    public TemporarySessionController(TemporarySessionStore sessions,
                                      TemporarySessionService service) {
        this.sessions = sessions;
        this.service = service;
    }

    /** 幂等创建 session；已有 session 直接返回，不滑动延长 24 小时过期时间。 */
    @PostMapping
    public ResponseEntity<TemporarySessionView> create() {
        return ResponseEntity.status(HttpStatus.CREATED).body(sessions.create(CurrentUser.id()));
    }

    /** 查询当前有效 session，不延长过期时间；不存在时返回 404。 */
    @GetMapping
    public TemporarySessionView current() {
        TemporarySessionView current = sessions.current(CurrentUser.id());
        if (current == null) throw AiApiException.resourceNotFound();
        return current;
    }

    /** 校验共享 run guard 后删除全部可丢弃内容；存在活动 turn 时返回 409，不隐式取消。 */
    @DeleteMapping
    public ResponseEntity<Void> delete() {
        service.delete(CurrentUser.id());
        return ResponseEntity.noContent().build();
    }
}
