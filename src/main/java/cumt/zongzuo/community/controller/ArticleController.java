package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.ArticleDTO;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.service.ArticleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

    // 获取文章详情
    @GetMapping("/detail/{id}")
    public Result<Article> getDetail(@PathVariable Long id) {
        Article article = articleService.getDetail(id);
        return Result.success(article);
    }



    // ...

    // 查询指定用户的文章列表 (支持分页)
    // GET /api/article/user/{userId}?page=1&size=10
    @GetMapping("/user/{userId}")
    public Result<Page<Article>> getUserArticles(@PathVariable Long userId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int size) {
        Page<Article> pageResult = articleService.getUserArticles(userId, page, size);
        return Result.success(pageResult);
    }
}