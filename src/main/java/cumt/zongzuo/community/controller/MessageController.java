package cumt.zongzuo.community.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.entity.Message;
import cumt.zongzuo.community.service.MessageService;
import cumt.zongzuo.community.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/message")
public class MessageController {

    @Autowired
    private MessageService messageService;

    // 获取未读数量 (用于显示在铃铛上)
    @GetMapping("/unread")
    public Result<Long> getUnreadCount(@RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        Long count = messageService.getUnreadCount(userId);
        return Result.success(count);
    }

    // 获取消息列表
    @GetMapping("/list")
    public Result<Page<Message>> getList(@RequestHeader("token") String token,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "10") int size) {
        Long userId = JwtUtils.getUserId(token);
        Page<Message> list = messageService.getMyMessages(userId, page, size);
        return Result.success(list);
    }

    // 全部已读
    @PostMapping("/read-all")
    public Result<String> readAll(@RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        messageService.readAll(userId);
        return Result.success("操作成功");
    }
}