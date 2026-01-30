package cumt.zongzuo.community.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cumt.zongzuo.community.dto.ArticleDTO;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.UserMapper;
import cumt.zongzuo.community.service.ArticleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements ArticleService {

    @Autowired
    private UserMapper userMapper;

    @Override
    public List<Article> getHotArticles() {
        // 查询最新的 10 篇文章，按创建时间倒序
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.orderByDesc("create_time").last("limit 10");
        return list(query);
    }

    @Override
    public List<Article> getFeedArticles(String lastCreateTime) {
        QueryWrapper<Article> query = new QueryWrapper<>();

        // 如果前端传了时间，就查这个时间之前的数据
        if (StrUtil.isNotBlank(lastCreateTime)) {
            query.lt("create_time", lastCreateTime); // lt = less than (<)
        }

        query.orderByDesc("create_time")
                .last("limit 10"); // 每次只拿10条

        return list(query);
    }
    @Override
    public List<Article> getHotRank() {
        QueryWrapper<Article> query = new QueryWrapper<>();
        // 只查询 id 和 title，减少数据库传输压力
        query.select("id", "title");
        // 按浏览量倒序
        query.orderByDesc("view_count");
        // 取前 10 条
        query.last("limit 10");

        return list(query);
    }

    @Override
    public void publishArticle(ArticleDTO dto, Long userId) {
        Article article = new Article();
        article.setTitle(dto.getTitle());
        article.setContent(dto.getContent());

        // 如果前端没传摘要，自动截取前100个字
        if (dto.getSummary() == null || dto.getSummary().isEmpty()) {
            String cleanContent = dto.getContent().replaceAll("#|`|\\*", ""); // 简单去除Markdown符号
            article.setSummary(cleanContent.length() > 100 ? cleanContent.substring(0, 100) + "..." : cleanContent);
        } else {
            article.setSummary(dto.getSummary());
        }

        article.setAuthorId(userId);

        // 查询作者信息 (为了冗余存储 authorName，减少连表查询)
        User user = userMapper.selectById(userId);
        if (user != null) {
            article.setAuthorName(user.getUsername());
        }

        article.setViewCount(0);
        article.setLikeCount(0);
        // createTime 由数据库自动生成，或这里手动 setLocalDateTime.now()
        save(article);
    }

}