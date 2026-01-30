package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.ArticleDTO;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.service.ArticleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/article")
public class ArticleController {

    @Autowired
    private ArticleService articleService;

    // 获取热门文章列表 (公开接口)
    @GetMapping("/hot")
    public Result<List<Article>> getHotArticles() {
        List<Article> list = articleService.getHotArticles();
        return Result.success(list);
    }
    @GetMapping("/feed")
    public Result<List<Article>> getFeedArticles(@RequestParam(required = false) String lastCreateTime) {
        List<Article> list = articleService.getFeedArticles(lastCreateTime);
        return Result.success(list);
    }

    // 获取右侧全局热榜
    @GetMapping("/hot-rank")
    public Result<List<Article>> getHotRank() {
        List<Article> list = articleService.getHotRank();
        return Result.success(list);
    }

    // 发布文章
    // @RequestHeader("token") 获取 token，解析出 userId
    @PostMapping("/publish")
    public Result<String> publish(@RequestBody ArticleDTO dto, @RequestHeader("token") String token) {
        // 从 Token 获取用户 ID
        Long userId = cumt.zongzuo.community.utils.JwtUtils.getUserId(token);

        articleService.publishArticle(dto, userId);
        return Result.success("发布成功");
    }
}