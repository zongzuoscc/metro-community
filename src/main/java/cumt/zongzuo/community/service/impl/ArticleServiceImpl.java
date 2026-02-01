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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements ArticleService {

    @Autowired
    private UserMapper userMapper;

    // 使用 StringRedisTemplate 以支持自增操作和可读性
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 定义常量
    private static final String ARTICLE_VIEW_COUNT_KEY = "article:view:count:";
    public static final String ARTICLE_VIEW_DIRTY_SET = "article:view:dirty:ids";

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

        // 摘要处理
        if (dto.getSummary() == null || dto.getSummary().isEmpty()) {
            String cleanContent = dto.getContent().replaceAll("#|`|\\*", "");
            article.setSummary(cleanContent.length() > 100 ? cleanContent.substring(0, 100) + "..." : cleanContent);
        } else {
            article.setSummary(dto.getSummary());
        }

        // 核心：只存 ID，不存 Name
        article.setAuthorId(userId);
        // article.setAuthorName(...) <--- 这行代码删掉，因为数据库没这个列了

        article.setViewCount(0);
        article.setLikeCount(0);

        // 手动设置时间
        LocalDateTime now = LocalDateTime.now();
        article.setCreateTime(now);
        article.setUpdateTime(now);

        save(article);
    }

    @Override
    public Article getDetail(Long id) {
        // 1. 查询文章基础信息
        Article article = getById(id);
        if (article == null) {
            throw new RuntimeException("文章不存在");
        }

        // 2. 填充作者信息 (因为数据库去掉了 author_name，必须查 User)
        Long authorId = article.getAuthorId();
        if (authorId != null) {
            User author = userMapper.selectById(authorId);
            if (author != null) {
                article.setAuthorName(author.getUsername());
                article.setAuthorAvatar(author.getAvatar());
            } else {
                article.setAuthorName("用户已注销");
            }
        }

        // 3. 处理浏览量 (Redis 计数)
        String viewCountKey = ARTICLE_VIEW_COUNT_KEY + id;

        // 如果 Redis 里没有这个 key (比如缓存过期或刚发布)，先从 DB 初始化
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(viewCountKey))) {
            // 设置 24 小时过期，防止冷数据占用内存
            stringRedisTemplate.opsForValue().set(viewCountKey, article.getViewCount().toString(), 24, TimeUnit.HOURS);
        }

        // Redis 原子 +1
        Long newViewCount = stringRedisTemplate.opsForValue().increment(viewCountKey);

        // 赋值给返回值 (只改变内存对象，不写库)
        if (newViewCount != null) {
            article.setViewCount(newViewCount.intValue());
        }

        // 4. 标记脏数据，等待定时任务同步
        stringRedisTemplate.opsForSet().add(ARTICLE_VIEW_DIRTY_SET, id.toString());

        return article;
    }

}