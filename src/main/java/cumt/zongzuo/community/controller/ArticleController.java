package cumt.zongzuo.community.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.ArticleDTO;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.entity.Tag;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.mapper.TagMapper;
import cumt.zongzuo.community.service.ArticleService;
import cumt.zongzuo.community.service.UserService;
import cumt.zongzuo.community.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import cumt.zongzuo.community.annotation.RateLimit; // 记得导包

@RestController
@RequestMapping("/api/article")
public class ArticleController {

    @Autowired
    private ArticleService articleService;

    @Autowired
    private UserService userService;

    @Autowired
    private TagMapper tagMapper; // 临时注入

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
    @RateLimit(name = "publish_article", time = 20, count = 1)
    public Result<Long> publish(@RequestBody ArticleDTO dto, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);

        // 【核心修复】
        // 从 DTO 获取状态，如果没传(null)则默认为 true(发布)
        boolean isPublish = dto.getIsPublish() != null ? dto.getIsPublish() : true;

        return Result.success(articleService.publishOrSave(dto, isPublish, userId));
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

    // 【新增】获取草稿数量
    @GetMapping("/draft-count")
    public Result<Long> getDraftCount(@RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        return Result.success(articleService.getDraftCount(userId));
    }

    // 【新增】7天热榜接口
    @GetMapping("/hot-feed")
    public Result<List<Article>> getHotFeed() {
        return Result.success(articleService.getHotArticles7Days());
    }

    // 【新增】关注流接口
    @GetMapping("/follow-feed")
    public Result<Page<Article>> getFollowFeed(@RequestParam(defaultValue = "1") int page,
                                               @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
        // 默认每页 10 条
        return Result.success(articleService.getFollowArticles(userId, page, 10));
    }

    // 【新增】搜索接口
    @GetMapping("/search")
    public Result<Page<Article>> search(@RequestParam String keyword,
                                        @RequestParam(defaultValue = "1") int page) {
        // 默认每页 10 条
        return Result.success(articleService.searchArticles(keyword, page, 10));
    }

    /**
     * 【个人中心】获取我的文章列表（包含待审核等所有状态）
     */
    @GetMapping("/my/list")
    public Result<Page<Article>> getMyList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestHeader("token") String token) {

        Long userId = JwtUtils.getUserId(token);
        return Result.success(articleService.getMyAllArticles(userId, page, size));
    }

    @GetMapping("/admin/pending")
    public Result<Page<Article>> getPendingList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestHeader("token") String token) {

        checkAdmin(token);
        return Result.success(articleService.getPendingArticles(page, size));
    }

    @PostMapping("/admin/audit")
    public Result<String> auditArticle(
            @RequestBody Map<String, Object> params,
            @RequestHeader("token") String token) {

        checkAdmin(token);
        Long articleId = Long.valueOf(params.get("id").toString());
        boolean pass = Boolean.parseBoolean(params.get("pass").toString());
        String reason = (String) params.get("reason");

        articleService.auditArticle(articleId, pass, reason);
        return Result.success("操作成功");
    }

    private void checkAdmin(String token) {
        Long userId = JwtUtils.getUserId(token);
        User user = userService.getById(userId);
        // 【关键修复】增加 null 判断，防止 NPE
        if (user == null || user.getRole() == null || user.getRole() != 1) {
            throw new RuntimeException("无权访问");
        }
    }
}