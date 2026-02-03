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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
        // 1. 查询最新的 10 篇文章
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.orderByDesc("create_time").last("limit 10");
        List<Article> list = list(query);

        // 2. 【新增】填充作者信息
        fillArticleAuthors(list);

        return list;
    }

    @Override
    public List<Article> getFeedArticles(String lastCreateTime) {
        QueryWrapper<Article> query = new QueryWrapper<>();
        if (StrUtil.isNotBlank(lastCreateTime)) {
            query.lt("create_time", lastCreateTime);
        }
        query.orderByDesc("create_time").last("limit 10");

        List<Article> list = list(query);

        // 2. 【新增】填充作者信息
        fillArticleAuthors(list);

        return list;
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
            // 【新增日志】打印接收到的ID，方便确认是否发生了精度丢失 (例如收到 ...00 结尾的ID)
            System.err.println("❌ 查询文章失败，数据库中未找到 ID 为: " + id + " 的文章");
            throw new RuntimeException("文章不存在");
        }

        // 2. 填充作者信息 (昵称、头像、简介、统计数据)
        Long authorId = article.getAuthorId();
        if (authorId != null) {
            User author = userMapper.selectById(authorId);
            if (author != null) {
                // 基础信息
                article.setAuthorName(author.getUsername());
                article.setAuthorAvatar(author.getAvatar());
                // 【新增】简介
                article.setAuthorIntro(author.getIntro());

                // 【新增】统计数据
                // 1. 文章总数 (直接用 MyBatis-Plus 的 count 方法)
                Long articleCount = this.count(new QueryWrapper<Article>().eq("author_id", authorId));
                article.setAuthorArticleCount(articleCount);

                // 2. 获赞总数 (调用刚才在 Mapper 写的 SQL)
                Long totalLikes = baseMapper.sumLikesByAuthorId(authorId);
                article.setAuthorTotalLikes(totalLikes);

            } else {
                article.setAuthorName("用户已注销");
                article.setAuthorAvatar("https://cube.elemecdn.com/9/c2/f0ee8a3c7c9638a54940382568c9dpng.png");
                article.setAuthorIntro("该用户已离家出走");
                article.setAuthorArticleCount(0L);
                article.setAuthorTotalLikes(0L);
            }
        }

        // 3. 处理浏览量 (Redis 计数)
        String viewCountKey = ARTICLE_VIEW_COUNT_KEY + id;

        // 如果 Redis 里没有这个 key (比如缓存过期或刚发布)，先从 DB 初始化
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(viewCountKey))) {
            // 【核心修复】防止数据库 view_count 为 null 导致 .toString() 报空指针异常
            Integer dbViewCount = article.getViewCount();
            // 如果是 null 则默认为 "0"
            String initValue = (dbViewCount == null) ? "0" : dbViewCount.toString();

            // 设置 24 小时过期，防止冷数据占用内存
            stringRedisTemplate.opsForValue().set(viewCountKey, initValue, 24, TimeUnit.HOURS);
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

    /**
     * 私有辅助方法：批量填充文章列表的作者信息
     * 原理：拿到一堆文章 -> 提取所有作者ID -> 一次性查出所有User -> 填回去
     */
    private void fillArticleAuthors(List<Article> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }

        // 1. 提取所有不重复的 authorId
        // 需要导入: java.util.Set, java.util.stream.Collectors
        Set<Long> userIds = articles.stream()
                .map(Article::getAuthorId)
                .collect(Collectors.toSet());

        // 2. 批量查询 User 表 (SELECT * FROM sys_user WHERE id IN (...))
        // 需要导入: java.util.Map
        List<User> users = userMapper.selectBatchIds(userIds);

        // 3. 转成 Map 方便匹配 (Key: userId, Value: User对象)
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        // 4. 回填数据
        for (Article article : articles) {
            User u = userMap.get(article.getAuthorId());
            if (u != null) {
                article.setAuthorName(u.getUsername());
                article.setAuthorAvatar(u.getAvatar());
            } else {
                article.setAuthorName("注销用户");
                article.setAuthorAvatar("https://cube.elemecdn.com/9/c2/f0ee8a3c7c9638a54940382568c9dpng.png"); // 默认头像
            }
        }
    }

}