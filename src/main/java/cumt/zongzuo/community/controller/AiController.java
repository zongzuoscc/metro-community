package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.service.ArticleService;
import cumt.zongzuo.community.service.MetroAiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    @Autowired
    private MetroAiService metroAiService;

    @Autowired
    private ArticleService articleService;

    @GetMapping("/chat")
    public Result<String> chat(@RequestParam String msg) {
        // 调用 AI 服务
        String aiResponse = metroAiService.chatWithMetro(msg);
        return Result.success(aiResponse);
    }

    /**
     * 一键总结文章接口
     */
    @GetMapping("/summarize/{articleId}")
    public Result<String> summarize(@PathVariable Long articleId) {
        Article article = articleService.getById(articleId);
        if (article == null) {
            return Result.error("文章不存在");
        }
        // 调用我们刚刚写好的 AI 总结方法
        String summary = metroAiService.summarizeArticle(article.getContent());
        return Result.success(summary);
    }
}