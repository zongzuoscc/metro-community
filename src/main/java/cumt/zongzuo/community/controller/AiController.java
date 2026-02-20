package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.service.MetroAiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    @Autowired
    private MetroAiService metroAiService;

    @GetMapping("/chat")
    public Result<String> chat(@RequestParam String msg) {
        // 调用 AI 服务
        String aiResponse = metroAiService.chatWithMetro(msg);
        return Result.success(aiResponse);
    }
}