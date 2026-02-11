package cumt.zongzuo.community.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.dto.ArticleDTO;
import cumt.zongzuo.community.entity.*;
import cumt.zongzuo.community.mapper.*;
import cumt.zongzuo.community.service.ArticleService;
import cumt.zongzuo.community.service.UserService;
import cumt.zongzuo.community.utils.JwtUtils;
import cumt.zongzuo.community.utils.SensitiveUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements ArticleService {

    @Autowired
    private TagMapper tagMapper;

    @Autowired
    private ArticleTagMapper articleTagMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SensitiveUtils sensitiveUtils;

    @Autowired
    private FollowMapper followMapper;

    // Redis Key 定义
    private static final String ARTICLE_DETAIL_CACHE_PREFIX = "article:detail:";
    private static final String ARTICLE_VIEW_COUNT_KEY = "article:view:count:";
    public static final String ARTICLE_VIEW_DIRTY_SET = "article:view:dirty:set";
    private static final String HOT_RANK_CACHE_KEY = "hot:article:rank:7days";

    // --------------------------------------------------------------------------------
    // 1. 发布/保存文章 (含机器审核逻辑)
    // --------------------------------------------------------------------------------
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
            article.setUpdateTime(LocalDateTime.now());
        } else {
            // --- 新增逻辑 ---
            article = new Article();
            article.setAuthorId(userId);
            article.setCreateTime(LocalDateTime.now());
            article.setUpdateTime(LocalDateTime.now());
            article.setViewCount(0);
            article.setLikeCount(0);
            article.setCommentCount(0);
            article.setCollectCount(0); // 这里需要 Article.java 中有 collectCount 字段
            article.setIsDeleted(0);
        }

        // 2. 【核心】敏感词机器审核 (仅发布时校验)
        if (isPublish) {
            String fullText = (dto.getTitle() == null ? "" : dto.getTitle())
                    + (dto.getContent() == null ? "" : dto.getContent());
            // 极速同步检测
            String sensitiveWord = sensitiveUtils.check(fullText);
            if (sensitiveWord != null) {
                // 发现违规，直接抛异常阻断，前端会收到错误提示
                throw new RuntimeException("发布失败：内容包含违规词汇 [" + sensitiveWord + "]，请修改");
            }
        }

        // 3. 填充基础字段
        article.setTitle(dto.getTitle());
        article.setContent(dto.getContent());
        article.setCover(dto.getCover());

        // 自动生成摘要
        if (StrUtil.isBlank(dto.getSummary())) {
            String content = dto.getContent() == null ? "" : dto.getContent();
            content = content.replaceAll("!\\[.*?\\]\\(.*?\\)", ""); // 去图片
            String cleanTxt = content.replaceAll("[#*>`~-]", "").trim(); // 去Markdown符号
            article.setSummary(cleanTxt.length() > 100 ? cleanTxt.substring(0, 100) + "..." : cleanTxt);
        } else {
            article.setSummary(dto.getSummary());
        }

        // 4. 【核心】状态设置逻辑
        if (isPublish) {
            // 机器审核通过 -> 进入人工审核状态 (2)
            // 只有管理员后台通过后，状态才会变为 1 (已发布/公开)
            article.setStatus(2);
        } else {
            // 存草稿
            article.setStatus(0);
        }

        // 5. 落库
        saveOrUpdate(article);

        // 6. 处理标签
        handleTags(article.getId(), dto.getTags());

        // 7. 删除缓存 (保证数据一致性)
        if (dto.getId() != null) {
            stringRedisTemplate.delete(ARTICLE_DETAIL_CACHE_PREFIX + dto.getId());
        }

        return article.getId();
    }

    /**
     * 兼容旧接口
     */
    @Override
    public void publishArticle(ArticleDTO dto, Long userId) {
        publishOrSave(dto, true, userId);
    }

    // --------------------------------------------------------------------------------
    // 2. 查询文章详情 (含权限/可见性控制)
    // --------------------------------------------------------------------------------
    @Override
    public Article getDetail(Long id) {
        // 1. 先查 Redis 缓存
        String cacheKey = ARTICLE_DETAIL_CACHE_PREFIX + id;
        String json = stringRedisTemplate.opsForValue().get(cacheKey);

        Article article = null;
        if (StrUtil.isNotBlank(json)) {
            try {
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

            // 填充作者信息
            fillArticleAuthorInfo(article);
            // 填充标签
            fillArticleTags(article);

            // 3. 写入 Redis (过期时间 1 小时)
            try {
                String cacheValue = objectMapper.writeValueAsString(article);
                stringRedisTemplate.opsForValue().set(cacheKey, cacheValue, 1, TimeUnit.HOURS);
            } catch (Exception e) {
                log.error("文章详情写入缓存失败", e);
            }
        }

        // 4. 【核心】可见性权限校验
        // 如果文章不是 "已发布(1)" 状态，只有作者本人或管理员可见
        if (article.getStatus() != 1) {
            Long currentUserId = tryGetCurrentUserId();
            boolean isAuthor = article.getAuthorId().equals(currentUserId);

            // 简单判断管理员权限 (假设 Role=1 是管理员)
            boolean isAdmin = false;
            if (currentUserId != null) {
                User currentUser = userService.getById(currentUserId);
                // 这里需要 User.java 中有 role 字段
                if (currentUser != null && Integer.valueOf(1).equals(currentUser.getRole())) {
                    isAdmin = true;
                }
            }

            if (!isAuthor && !isAdmin) {
                throw new RuntimeException("文章正在审核中，仅作者可见");
            }
        }

        // 5. 浏览量处理 (Redis 实时计数)
        String viewCountKey = ARTICLE_VIEW_COUNT_KEY + id;
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(viewCountKey))) {
            stringRedisTemplate.opsForValue().set(viewCountKey, String.valueOf(article.getViewCount()));
        }
        Long newViewCount = stringRedisTemplate.opsForValue().increment(viewCountKey);
        article.setViewCount(newViewCount.intValue()); // 视图层展示最新值

        // 标记脏数据等待同步
        stringRedisTemplate.opsForSet().add(ARTICLE_VIEW_DIRTY_SET, id.toString());

        return article;
    }

    // --------------------------------------------------------------------------------
    // 3. 列表查询相关
    // --------------------------------------------------------------------------------

    @Override
    public List<Article> getHotArticles() {
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.eq("status", 1).eq("is_deleted", 0);
        query.orderByDesc("create_time").last("limit 10");
        List<Article> list = list(query);
        fillArticleAuthors(list);
        return list;
    }

    @Override
    public List<Article> getFeedArticles(String lastCreateTime) {
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.eq("status", 1).eq("is_deleted", 0);
        if (StrUtil.isNotBlank(lastCreateTime)) {
            query.lt("create_time", lastCreateTime);
        }
        query.orderByDesc("create_time").last("limit 10");
        List<Article> list = list(query);
        fillArticleAuthors(list);
        return list;
    }

    @Override
    public List<Article> getHotRank() {
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.eq("status", 1).eq("is_deleted", 0);
        query.select("id", "title");
        query.orderByDesc("view_count");
        query.last("limit 10");
        return list(query);
    }

    @Override
    public Page<Article> getUserArticles(Long userId, int pageNo, int pageSize) {
        Page<Article> page = new Page<>(pageNo, pageSize);
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("author_id", userId);
        wrapper.eq("status", 1).eq("is_deleted", 0);
        wrapper.orderByDesc("create_time");
        return page(page, wrapper);
    }

    @Override
    public Page<Article> getFollowArticles(Long userId, int pageNo, int pageSize) {
        Page<Article> page = new Page<>(pageNo, pageSize);
        List<Follow> follows = followMapper.selectList(new QueryWrapper<Follow>().eq("follower_id", userId));
        if (follows.isEmpty()) {
            return page;
        }
        Set<Long> followedIds = follows.stream().map(Follow::getFollowedId).collect(Collectors.toSet());
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.in("author_id", followedIds);
        query.eq("status", 1).eq("is_deleted", 0);
        query.orderByDesc("create_time");
        page(page, query);
        fillArticleAuthors(page.getRecords());
        return page;
    }

    @Override
    public Page<Article> searchArticles(String keyword, int page, int size) {
        Page<Article> pageInfo = new Page<>(page, size);
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.eq("status", 1).eq("is_deleted", 0);

        if (StrUtil.isNotBlank(keyword)) {
            // 使用 apply 子查询：查出 tag.name 包含 keyword 的所有 article_id
            String tagSubQuery = "id IN (SELECT at.article_id FROM article_tag at " +
                    "LEFT JOIN tag t ON at.tag_id = t.id " +
                    "WHERE t.name LIKE {0})";

            query.and(w -> w.like("title", keyword)
                    .or().like("summary", keyword)
                    .or().like("content", keyword)
                    .or().apply(tagSubQuery, "%" + keyword + "%")
            );
        }

        query.orderByDesc("create_time");
        Page<Article> result = page(pageInfo, query);
        fillArticleAuthors(result.getRecords());
        result.getRecords().forEach(this::fillArticleTags);
        return result;
    }

    // --------------------------------------------------------------------------------
    // 4. 管理员审核与回收站
    // --------------------------------------------------------------------------------

    @Override
    public Page<Article> getPendingArticles(int page, int size) {
        Page<Article> pageInfo = new Page<>(page, size);
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.eq("status", 2).orderByAsc("create_time");
        Page<Article> result = page(pageInfo, query);
        if (result.getRecords() != null) {
            for (Article a : result.getRecords()) {
                fillArticleAuthorInfo(a);
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void auditArticle(Long articleId, boolean pass, String reason) {
        Article article = getById(articleId);
        if (article == null) throw new RuntimeException("文章不存在");
        if (pass) {
            article.setStatus(1); // 通过
        } else {
            article.setStatus(3); // 拒绝
        }
        updateById(article);
        stringRedisTemplate.delete(ARTICLE_DETAIL_CACHE_PREFIX + articleId);
    }

    @Override
    public void moveToRecycleBin(Long articleId, Long userId) {
        Article article = getById(articleId);
        if (article == null) return;
        if (!article.getAuthorId().equals(userId)) throw new RuntimeException("无权删除");
        article.setIsDeleted(1);
        article.setDeleteTime(LocalDateTime.now());
        updateById(article);
        stringRedisTemplate.delete(ARTICLE_DETAIL_CACHE_PREFIX + articleId);
    }

    @Override
    public void deleteArticle(Long articleId, Long userId) {
        moveToRecycleBin(articleId, userId);
    }

    @Override
    public void restoreArticle(Long articleId, Long userId) {
        Article article = getById(articleId);
        if (article == null) return;
        if (!article.getAuthorId().equals(userId)) throw new RuntimeException("无权操作");
        article.setIsDeleted(0);
        article.setDeleteTime(null);
        updateById(article);
        stringRedisTemplate.delete(ARTICLE_DETAIL_CACHE_PREFIX + articleId);
    }

    @Override
    public void deletePermanently(Long articleId, Long userId) {
        Article article = getById(articleId);
        if (article == null) return;
        if (!article.getAuthorId().equals(userId)) throw new RuntimeException("无权操作");
        removeById(articleId);
    }

    @Override
    public List<Article> getRecycleBin(Long userId) {
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("author_id", userId);
        wrapper.eq("is_deleted", 1);
        wrapper.orderByDesc("delete_time");
        return list(wrapper);
    }

    @Override
    public void cleanExpiredArticles() {
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("is_deleted", 1);
        wrapper.le("delete_time", sevenDaysAgo);
        remove(wrapper);
    }

    @Override
    public List<Article> getMyDrafts(Long userId) {
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("author_id", userId);
        wrapper.eq("status", 0);
        wrapper.eq("is_deleted", 0);
        wrapper.orderByDesc("create_time");
        return list(wrapper);
    }

    @Override
    public Article getArticleForEdit(Long articleId, Long userId) {
        Article article = getById(articleId);
        if (article == null) throw new RuntimeException("文章不存在");
        if (!article.getAuthorId().equals(userId)) throw new RuntimeException("无权编辑");
        fillArticleTags(article);
        return article;
    }

    @Override
    public Long getDraftCount(Long userId) {
        return count(new QueryWrapper<Article>()
                .eq("author_id", userId)
                .eq("status", 0)
                .eq("is_deleted", 0));
    }

    @Override
    public List<Article> getHotArticles7Days() {
        String json = stringRedisTemplate.opsForValue().get(HOT_RANK_CACHE_KEY);
        if (StrUtil.isNotBlank(json)) {
            try {
                return objectMapper.readValue(json, new TypeReference<List<Article>>() {});
            } catch (Exception e) {
                log.error("热榜缓存解析失败", e);
            }
        }
        return queryHotArticlesFromDB();
    }

    @Override
    public void updateHotRankCache() {
        List<Article> hotArticles = queryHotArticlesFromDB();
        try {
            String json = objectMapper.writeValueAsString(hotArticles);
            stringRedisTemplate.opsForValue().set(HOT_RANK_CACHE_KEY, json);
        } catch (Exception e) {
            log.error("热榜缓存写入失败", e);
        }
    }

    // --------------------------------------------------------------------------------
    // 5. 辅助方法
    // --------------------------------------------------------------------------------

    private List<Article> queryHotArticlesFromDB() {
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.eq("status", 1).eq("is_deleted", 0);
        query.ge("create_time", sevenDaysAgo);
        query.orderByDesc("view_count");
        query.last("limit 10");
        List<Article> list = list(query);
        fillArticleAuthors(list);
        list.forEach(this::fillArticleTags);
        return list;
    }

    private void handleTags(Long articleId, List<String> tagNames) {
        if (tagNames == null) return;
        articleTagMapper.delete(new QueryWrapper<ArticleTag>().eq("article_id", articleId));
        List<String> distinctTags = tagNames.stream().distinct().limit(5).collect(Collectors.toList());
        for (String tagName : distinctTags) {
            tagName = tagName.trim();
            if (tagName.isEmpty()) continue;
            Tag tag = tagMapper.selectOne(new QueryWrapper<Tag>().eq("name", tagName));
            if (tag == null) {
                tag = new Tag();
                tag.setName(tagName);
                tag.setArticleCount(1);
                tag.setCreateTime(LocalDateTime.now());
                tagMapper.insert(tag);
            } else {
                tag.setArticleCount(tag.getArticleCount() + 1);
                tagMapper.updateById(tag);
            }
            ArticleTag relation = new ArticleTag();
            relation.setArticleId(articleId);
            relation.setTagId(tag.getId());
            articleTagMapper.insert(relation);
        }
    }

    // 【核心修复】改为通用的 MyBatis-Plus 写法，避免 xml 方法未定义错误
    private void fillArticleTags(Article article) {
        if (article == null) return;

        // 1. 先查关联表
        List<ArticleTag> relations = articleTagMapper.selectList(
                new QueryWrapper<ArticleTag>().eq("article_id", article.getId())
        );

        if (relations.isEmpty()) {
            article.setTagList(new ArrayList<>());
            return;
        }

        // 2. 收集 TagID
        List<Long> tagIds = relations.stream().map(ArticleTag::getTagId).collect(Collectors.toList());

        // 3. 查 Tag 表
        List<Tag> tags = tagMapper.selectBatchIds(tagIds);

        // 4. 提取名字
        if (tags != null && !tags.isEmpty()) {
            List<String> names = tags.stream().map(Tag::getName).collect(Collectors.toList());
            article.setTagList(names);
        } else {
            article.setTagList(new ArrayList<>());
        }
    }

    private void fillArticleAuthors(List<Article> articles) {
        if (articles == null || articles.isEmpty()) return;
        Set<Long> userIds = articles.stream().map(Article::getAuthorId).collect(Collectors.toSet());
        Map<Long, User> userMap = userService.getUserMapCached(userIds);
        for (Article article : articles) {
            User u = userMap.get(article.getAuthorId());
            if (u != null) {
                article.setAuthorName(u.getUsername());
                article.setAuthorAvatar(u.getAvatar());
            } else {
                article.setAuthorName("注销用户");
            }
        }
    }

    private void fillArticleAuthorInfo(Article article) {
        if (article.getAuthorId() != null) {
            User author = userService.getUserCached(article.getAuthorId());
            if (author != null) {
                article.setAuthorName(author.getUsername());
                article.setAuthorAvatar(author.getAvatar());
                article.setAuthorIntro(author.getIntro());
            } else {
                article.setAuthorName("注销用户");
            }
        }
    }

    private Long tryGetCurrentUserId() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String token = request.getHeader("token");
                if (StrUtil.isNotBlank(token)) {
                    return JwtUtils.getUserId(token);
                }
            }
        } catch (Exception e) {}
        return null;
    }

    @Override
    public Page<Article> getMyAllArticles(Long userId, int page, int size) {
        Page<Article> pageInfo = new Page<>(page, size);
        QueryWrapper<Article> wrapper = new QueryWrapper<>();

        wrapper.eq("author_id", userId);
        wrapper.eq("is_deleted", 0);
        // 【关键】这里不写 .eq("status", 1)，这样就能查出状态 0(草稿), 1(发布), 2(审核中), 3(拒绝)

        wrapper.orderByDesc("create_time");
        return page(pageInfo, wrapper);
    }
}