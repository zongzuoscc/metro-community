package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.ArticleDTO;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.service.ArticleService;
import cumt.zongzuo.community.utils.JwtUtils;
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

    // ...

    // 1. 发布文章 (新增或修改)
    @PostMapping("/publish")
    public Result<Long> publish(@RequestBody ArticleDTO dto, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        // true 表示直接发布
        Long articleId = articleService.publishOrSave(dto, true, userId);
        return Result.success(articleId);
    }

    // 2. 存为草稿 (新增或修改)
    @PostMapping("/draft")
    public Result<Long> saveDraft(@RequestBody ArticleDTO dto, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        // false 表示存草稿
        Long articleId = articleService.publishOrSave(dto, false, userId);
        return Result.success(articleId);
    }

    // 4. 获取草稿列表
    @GetMapping("/drafts")
    public Result<List<Article>> getDrafts(@RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        List<Article> list = articleService.getMyDrafts(userId);
        return Result.success(list);
    }

    // 5. 获取编辑详情 (回显数据用)
    @GetMapping("/edit/{id}")
    public Result<Article> getForEdit(@PathVariable Long id, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        Article article = articleService.getArticleForEdit(id, userId);
        return Result.success(article);
    }

    // ...

    // 1. 【修改】删除文章 -> 移入回收站
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        // 调用新的软删除方法
        articleService.moveToRecycleBin(id, userId);
        return Result.success("已移入回收站");
    }

    // 2. 【新增】恢复文章
    @PostMapping("/restore/{id}")
    public Result<String> restore(@PathVariable Long id, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        articleService.restoreArticle(id, userId);
        return Result.success("恢复成功");
    }

    // 3. 【新增】彻底删除
    @DeleteMapping("/hard/{id}")
    public Result<String> hardDelete(@PathVariable Long id, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        articleService.deletePermanently(id, userId);
        return Result.success("彻底删除成功");
    }

    // 4. 【新增】获取回收站列表
    @GetMapping("/recycle-bin")
    public Result<List<Article>> getRecycleBin(@RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        List<Article> list = articleService.getRecycleBin(userId);
        return Result.success(list);
    }
}