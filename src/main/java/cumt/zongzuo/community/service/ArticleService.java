package cumt.zongzuo.community.service;
import com.baomidou.mybatisplus.extension.service.IService;
import cumt.zongzuo.community.dto.ArticleDTO;
import cumt.zongzuo.community.entity.Article;
import java.util.List;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page; // 引入Page

public interface ArticleService extends IService<Article> {
    // 查询热门文章列表
    List<Article> getHotArticles();

    List<Article> getFeedArticles(String lastCreateTime);

    List<Article> getHotRank();
    // ArticleService.java
    void publishArticle(ArticleDTO dto, Long userId);

    Article getDetail(Long id);

    // 分页查询某用户的文章
    Page<Article> getUserArticles(Long userId, int pageNo, int pageSize);

    // ...

    /**
     * 发布或保存草稿
     * @param dto 前端传来的数据 (包含 id, title, content, summary)
     * @param isPublish true-发布, false-存草稿
     * @param userId 当前作者ID
     * @return 文章ID
     */
    Long publishOrSave(ArticleDTO dto, boolean isPublish, Long userId);

    /**
     * 删除文章 (逻辑删除或物理删除)
     */
    void deleteArticle(Long articleId, Long userId);

    /**
     * 获取我的草稿列表
     */
    List<Article> getMyDrafts(Long userId);

    /**
     * 获取文章详情用于编辑 (需要校验权限)
     */
    Article getArticleForEdit(Long articleId, Long userId);

    // ... 原有接口 ...

    // 【修改】原有的删除改为 "移入回收站"
    void moveToRecycleBin(Long articleId, Long userId);

    // 【新增】恢复文章
    void restoreArticle(Long articleId, Long userId);

    // 【新增】彻底删除 (物理删除)
    void deletePermanently(Long articleId, Long userId);

    // 【新增】获取回收站列表
    List<Article> getRecycleBin(Long userId);

    // 【新增】清理过期文章 (供定时任务调用)
    void cleanExpiredArticles();
    // 【新增】获取草稿数量
    Long getDraftCount(Long userId);

    // 【新增】获取7天内的热榜文章 (主页显示)
    List<Article> getHotArticles7Days();

    // 【新增】获取关注的人的文章 (分页)
    Page<Article> getFollowArticles(Long userId, int pageNo, int pageSize);

    // 【新增】搜索文章
    Page<Article> searchArticles(String keyword, int page, int size);
    // 【新增】更新热榜缓存 (供定时任务调用)
    void updateHotRankCache();
}