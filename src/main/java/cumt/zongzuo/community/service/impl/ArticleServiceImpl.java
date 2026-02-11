package cumt.zongzuo.community.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cumt.zongzuo.community.dto.ArticleDTO;
import cumt.zongzuo.community.entity.*;
import cumt.zongzuo.community.mapper.*;
import cumt.zongzuo.community.service.ArticleService;
import cumt.zongzuo.community.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements ArticleService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private FollowMapper followMapper;

    @Autowired
    private TagMapper tagMapper; // 【新增】
    @Autowired
    private ArticleTagMapper articleTagMapper; // 【新增】

    // 定义缓存 Key 前缀
    private static final String ARTICLE_DETAIL_CACHE_PREFIX = "article:detail:";

    @Autowired
    private UserService userService;

    // 定义常量
    private static final String ARTICLE_VIEW_COUNT_KEY = "article:view:count:";
    public static final String ARTICLE_VIEW_DIRTY_SET = "article:view:dirty:ids";

    // 定义 Redis Key
    private static final String HOT_RANK_CACHE_KEY = "hot:article:rank:7days";

    /**
     * 获取全站热榜 (status=1 已发布 AND is_deleted=0 未删除)
     */
    @Override
    public List<Article> getHotArticles() {
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.eq("status", 1).eq("is_deleted", 0); // 【关键过滤】
        query.orderByDesc("create_time").last("limit 10");
        List<Article> list = list(query);

        fillArticleAuthors(list);
        return list;
    }

    /**
     * 获取推荐流 (status=1 已发布 AND is_deleted=0 未删除)
     */
    @Override
    public List<Article> getFeedArticles(String lastCreateTime) {
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.eq("status", 1).eq("is_deleted", 0); // 【关键过滤】

        if (StrUtil.isNotBlank(lastCreateTime)) {
            query.lt("create_time", lastCreateTime);
        }
        query.orderByDesc("create_time").last("limit 10");

        List<Article> list = list(query);
        fillArticleAuthors(list);

        return list;
    }

    /**
     * 获取热榜排行 (status=1 已发布 AND is_deleted=0 未删除)
     */
    @Override
    public List<Article> getHotRank() {
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.eq("status", 1).eq("is_deleted", 0); // 【关键过滤】
        query.select("id", "title");
        query.orderByDesc("view_count");
        query.last("limit 10");
        return list(query);
    }

    /**
     * 【核心】发布文章或存草稿 (新增/修改)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publishOrSave(ArticleDTO dto, boolean isPublish, Long userId) {
        Article article;
        // 1. 判断是【新增】还是【修改】
        if (dto.getId() != null) {
            // --- 修改逻辑 ---
            article = getById(dto.getId());
            if (article == null) {
                throw new RuntimeException("文章不存在");
            }
            // 权限校验
            if (!article.getAuthorId().equals(userId)) {
                throw new RuntimeException("无权修改他人文章");
            }
            // 更新时间
            article.setUpdateTime(LocalDateTime.now());
        } else {
            // --- 新增逻辑 ---
            article = new Article();
            article.setAuthorId(userId);
            article.setCreateTime(LocalDateTime.now());
            article.setUpdateTime(LocalDateTime.now());
            article.setViewCount(0);
            article.setLikeCount(0);
            article.setCommentCount(0); // 记得初始化
            article.setIsDeleted(0); // 默认为正常状态
        }

        // 2. 填充通用字段
        article.setTitle(dto.getTitle());
        article.setContent(dto.getContent());
        article.setCover(dto.getCover()); // 封面图

        // 3. 自动生成摘要
        if (dto.getSummary() == null || dto.getSummary().isEmpty()) {
            String content = dto.getContent();
            // 【核心修复】先去除 Markdown 图片语法 ![...](...)
            content = content.replaceAll("!\\[.*?\\]\\(.*?\\)", "");
            // 再去除其他 Markdown 符号
            String cleanTxt = content.replaceAll("[#*>`~-]", "");
            // 去除多余空白
            cleanTxt = cleanTxt.trim();

            article.setSummary(cleanTxt.length() > 100 ? cleanTxt.substring(0, 100) + "..." : cleanTxt);
        } else {
            article.setSummary(dto.getSummary());
        }

        // 4. 设置状态 (1:发布, 0:草稿)
        article.setStatus(isPublish ? 1 : 0);

        // 5. 保存或更新
        saveOrUpdate(article);

        // ============ 【核心新增】处理标签 ============
        if (dto.getTags() != null) {
            // 1. 如果是编辑模式，先删除旧的关联
            if (dto.getId() != null) {
                articleTagMapper.delete(new QueryWrapper<ArticleTag>().eq("article_id", article.getId()));
                // (优化点：这里其实应该同步减少 tag 表里的 article_count，暂时省略)
            }

            // 2. 遍历新标签
            // 去重并限制数量 (例如最多5个)
            List<String> distinctTags = dto.getTags().stream().distinct().limit(5).collect(Collectors.toList());

            for (String tagName : distinctTags) {
                tagName = tagName.trim();
                if (tagName.isEmpty()) continue;

                // 查标签是否存在
                Tag tag = tagMapper.selectOne(new QueryWrapper<Tag>().eq("name", tagName));
                if (tag == null) {
                    // 不存在则创建
                    tag = new Tag();
                    tag.setName(tagName);
                    tag.setArticleCount(1);
                    tag.setCreateTime(LocalDateTime.now());
                    tagMapper.insert(tag);
                } else {
                    // 存在则计数+1
                    tag.setArticleCount(tag.getArticleCount() + 1);
                    tagMapper.updateById(tag);
                }

                // 建立关联
                ArticleTag relation = new ArticleTag();
                relation.setArticleId(article.getId());
                relation.setTagId(tag.getId());
                articleTagMapper.insert(relation);
            }
        }

        // 【新增】删除缓存 (如果是修改操作)
        if (dto.getId() != null) {
            stringRedisTemplate.delete(ARTICLE_DETAIL_CACHE_PREFIX + dto.getId());
        }

        return article.getId();
    }

    /**
     * 兼容旧接口，直接调用新逻辑
     */
    @Override
    public void publishArticle(ArticleDTO dto, Long userId) {
        publishOrSave(dto, true, userId);
    }

    /**
     * 获取文章详情
     */
    @Override
    public Article getDetail(Long id) {
        // 1. 先查 Redis 缓存
        String cacheKey = ARTICLE_DETAIL_CACHE_PREFIX + id;
        String json = stringRedisTemplate.opsForValue().get(cacheKey);

        Article article = null;
        if (StrUtil.isNotBlank(json)) {
            try {
                // 反序列化
                article = objectMapper.readValue(json, Article.class);
            } catch (Exception e) {
                log.error("文章详情缓存解析失败", e);
            }
        }

        // 2. 缓存未命中，查数据库 (回源)
        if (article == null) {
            article = getById(id);
            if (article == null) {
                throw new RuntimeException("文章不存在");
            }
            if (article.getIsDeleted() == 1) {
                throw new RuntimeException("文章已被删除");
            }

            // 填充作者信息 (使用我们之前写的 user cache 方法)
            if (article.getAuthorId() != null) {
                User author = userService.getUserCached(article.getAuthorId());
                if (author != null) {
                    article.setAuthorName(author.getUsername());
                    article.setAuthorAvatar(author.getAvatar());
                    article.setAuthorIntro(author.getIntro());
                    // ... 填充统计数据 (这部分如果不频繁变动也可以缓存，或者前端单独查) ...
                }
            }

            // 填充标签
            fillArticleTags(article);

            // 3. 写入 Redis (设置过期时间，例如 1 小时，防止冷数据长期占用内存)
            try {
                String cacheValue = objectMapper.writeValueAsString(article);
                stringRedisTemplate.opsForValue().set(cacheKey, cacheValue, 1, TimeUnit.HOURS);
            } catch (Exception e) {
                log.error("文章详情写入缓存失败", e);
            }
        }

        // ============ 浏览量单独处理 (Redis) ============
        // 注意：浏览量是实时变化的，不能用缓存里的旧值，必须覆盖
        String viewCountKey = ARTICLE_VIEW_COUNT_KEY + id;

        // 确保浏览量 Key 存在
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(viewCountKey))) {
            stringRedisTemplate.opsForValue().set(viewCountKey, article.getViewCount().toString());
        }

        // 自增
        Long newViewCount = stringRedisTemplate.opsForValue().increment(viewCountKey);
        article.setViewCount(newViewCount.intValue()); // 覆盖成最新的

        // 标记为脏数据，等待定时任务同步回库
        stringRedisTemplate.opsForSet().add(ARTICLE_VIEW_DIRTY_SET, id.toString());

        return article;
    }

    // ================== 回收站与删除逻辑 ==================

    /**
     * 移入回收站 (软删除)
     */
    @Override
    public void moveToRecycleBin(Long articleId, Long userId) {
        Article article = getById(articleId);
        if (article == null) return;

        if (!article.getAuthorId().equals(userId)) {
            throw new RuntimeException("无权删除");
        }

        article.setIsDeleted(1); // 标记为删除
        article.setDeleteTime(LocalDateTime.now()); // 记录删除时间
        updateById(article);
        // 【新增】删除缓存
        stringRedisTemplate.delete(ARTICLE_DETAIL_CACHE_PREFIX + articleId);
    }

    /**
     * 兼容旧接口名，实际上执行移入回收站逻辑
     */
    @Override
    public void deleteArticle(Long articleId, Long userId) {
        moveToRecycleBin(articleId, userId);
    }

    /**
     * 恢复文章
     */
    @Override
    public void restoreArticle(Long articleId, Long userId) {
        Article article = getById(articleId);
        if (article == null) return;
        if (!article.getAuthorId().equals(userId)) throw new RuntimeException("无权操作");

        article.setIsDeleted(0); // 恢复正常
        article.setDeleteTime(null);
        updateById(article);
        // 【新增】删除缓存
        stringRedisTemplate.delete(ARTICLE_DETAIL_CACHE_PREFIX + articleId);
    }

    /**
     * 彻底删除 (物理删除)
     */
    @Override
    public void deletePermanently(Long articleId, Long userId) {
        Article article = getById(articleId);
        if (article == null) return;
        if (!article.getAuthorId().equals(userId)) throw new RuntimeException("无权操作");

        removeById(articleId); // 物理删除
    }

    /**
     * 获取回收站列表 (is_deleted=1)
     */
    @Override
    public List<Article> getRecycleBin(Long userId) {
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("author_id", userId);
        wrapper.eq("is_deleted", 1);
        wrapper.orderByDesc("delete_time");
        return list(wrapper);
    }

    /**
     * 定时任务清理过期文章
     */
    @Override
    public void cleanExpiredArticles() {
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("is_deleted", 1);
        wrapper.le("delete_time", sevenDaysAgo);
        remove(wrapper);
    }

    // ================== 草稿箱与列表 ==================

    /**
     * 获取草稿列表 (status=0 AND is_deleted=0)
     */
    @Override
    public List<Article> getMyDrafts(Long userId) {
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("author_id", userId);
        wrapper.eq("status", 0);
        wrapper.eq("is_deleted", 0);
        wrapper.orderByDesc("create_time");
        return list(wrapper);
    }

    /**
     * 获取编辑详情 (回显)
     */
    @Override
    public Article getArticleForEdit(Long articleId, Long userId) {
        Article article = getById(articleId);
        if (article == null) throw new RuntimeException("文章不存在");
        if (!article.getAuthorId().equals(userId)) throw new RuntimeException("无权编辑");
        // 【优化】填充标签 (SQL Join)
        fillArticleTags(article);
        return article;
    }

    /**
     * 获取用户文章列表 (status=1 AND is_deleted=0)
     */
    @Override
    public Page<Article> getUserArticles(Long userId, int pageNo, int pageSize) {
        Page<Article> page = new Page<>(pageNo, pageSize);
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("author_id", userId);
        wrapper.eq("status", 1).eq("is_deleted", 0); // 【关键过滤】
        wrapper.orderByDesc("create_time");
        return page(page, wrapper);
    }

    /**
     * 辅助方法：填充作者信息
     */
    private void fillArticleAuthors(List<Article> articles) {
        if (articles == null || articles.isEmpty()) return;

        // 收集所有作者ID
        Set<Long> userIds = articles.stream().map(Article::getAuthorId).collect(Collectors.toSet());

        // 【优化】走 Redis 批量查询
        Map<Long, User> userMap = userService.getUserMapCached(userIds);

        for (Article article : articles) {
            User u = userMap.get(article.getAuthorId());
            if (u != null) {
                article.setAuthorName(u.getUsername());
                article.setAuthorAvatar(u.getAvatar());
                // 其他需要的字段...
            } else {
                article.setAuthorName("注销用户");
                article.setAuthorAvatar("https://cube.elemecdn.com/9/c2/f0ee8a3c7c9638a54940382568c9dpng.png");
            }
        }
    }

    @Override
    public Long getDraftCount(Long userId) {
        // 查询 status=0 (草稿) 且 is_deleted=0 (未删除) 的数量
        return count(new QueryWrapper<Article>()
                .eq("author_id", userId)
                .eq("status", 0)
                .eq("is_deleted", 0));
    }

    /**
     * 实现：7天热榜
     */
    @Override
    public List<Article> getHotArticles7Days() {
        // 1. 尝试从 Redis 获取
        String json = stringRedisTemplate.opsForValue().get(HOT_RANK_CACHE_KEY);
        if (StrUtil.isNotBlank(json)) {
            try {
                // 反序列化：JSON String -> List<Article>
                return objectMapper.readValue(json, new TypeReference<List<Article>>() {});
            } catch (Exception e) {
                log.error("热榜缓存解析失败", e);
            }
        }

        // 2. Redis 没有，查数据库 (兜底方案)
        return queryHotArticlesFromDB();
    }

    @Override
    public void updateHotRankCache() {
        // 1. 查数据库最新数据
        List<Article> hotArticles = queryHotArticlesFromDB();

        // 2. 写入 Redis
        try {
            String json = objectMapper.writeValueAsString(hotArticles);
            // 存入 Redis，不设置过期时间(依靠定时任务覆盖)，或者设置稍微长一点防止任务挂了
            stringRedisTemplate.opsForValue().set(HOT_RANK_CACHE_KEY, json);
        } catch (Exception e) {
            log.error("热榜缓存写入失败", e);
        }
    }

    /**
     * 提取出来的原始 DB 查询逻辑 (私有方法)
     */
    private List<Article> queryHotArticlesFromDB() {
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.eq("status", 1).eq("is_deleted", 0);
        query.ge("create_time", sevenDaysAgo);
        query.orderByDesc("view_count");
        query.last("limit 10");

        List<Article> list = list(query);
        fillArticleAuthors(list);
        // 填充标签 (如果之前做了标签功能)
        list.forEach(this::fillArticleTags);

        return list;
    }

    /**
     * 实现：关注流
     */
    @Override
    public Page<Article> getFollowArticles(Long userId, int pageNo, int pageSize) {
        Page<Article> page = new Page<>(pageNo, pageSize);

        // 1. 先查我关注了谁
        List<Follow> follows = followMapper.selectList(new QueryWrapper<Follow>().eq("follower_id", userId));
        if (follows.isEmpty()) {
            return page; // 没关注任何人，返回空页
        }

        Set<Long> followedIds = follows.stream().map(Follow::getFollowedId).collect(Collectors.toSet());

        // 2. 查这些人的文章
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.in("author_id", followedIds);        // 作者必须在关注列表里
        query.eq("status", 1).eq("is_deleted", 0);
        query.orderByDesc("create_time");          // 按时间倒序

        page(page, query);
        fillArticleAuthors(page.getRecords());
        return page;
    }

    private void fillArticleTags(Article article) {
        if (article == null) return;

        // 直接调用 XML 中定义的联表查询
        List<Tag> tags = tagMapper.selectTagsByArticleId(article.getId());

        // 提取标签名列表
        if (tags != null && !tags.isEmpty()) {
            List<String> tagNames = tags.stream().map(Tag::getName).collect(Collectors.toList());
            article.setTagList(tagNames);
        } else {
            article.setTagList(new ArrayList<>());
        }
    }



    @Override
    public Page<Article> searchArticles(String keyword, int page, int size) {
        Page<Article> pageInfo = new Page<>(page, size);
        QueryWrapper<Article> query = new QueryWrapper<>();

        // 基础条件
        query.eq("status", 1).eq("is_deleted", 0);

        if (StrUtil.isNotBlank(keyword)) {
            // 【核心修改】加入标签搜索逻辑
            // 使用 apply 子查询：查出 tag.name 包含 keyword 的所有 article_id
            String tagSubQuery = "id IN (SELECT at.article_id FROM article_tag at " +
                    "LEFT JOIN tag t ON at.tag_id = t.id " +
                    "WHERE t.name LIKE {0})";

            query.and(w -> w.like("title", keyword)
                    .or().like("summary", keyword)
                    .or().like("content", keyword)
                    .or().apply(tagSubQuery, "%" + keyword + "%") // 新增这一行
            );
        }

        query.orderByDesc("create_time");

        Page<Article> result = page(pageInfo, query);
        fillArticleAuthors(result.getRecords());

        // 【建议】搜索结果最好也把标签展示出来
        // (前提是你已经加上了 fillArticleTags 方法，如果没有，请参考上一条回答的第4步)
        result.getRecords().forEach(this::fillArticleTags);

        return result;
    }


}