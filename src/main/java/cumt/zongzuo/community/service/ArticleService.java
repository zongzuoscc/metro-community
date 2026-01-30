package cumt.zongzuo.community.service;
import com.baomidou.mybatisplus.extension.service.IService;
import cumt.zongzuo.community.dto.ArticleDTO;
import cumt.zongzuo.community.entity.Article;
import java.util.List;

public interface ArticleService extends IService<Article> {
    // 查询热门文章列表
    List<Article> getHotArticles();

    List<Article> getFeedArticles(String lastCreateTime);

    List<Article> getHotRank();
    // ArticleService.java
    void publishArticle(ArticleDTO dto, Long userId);
}