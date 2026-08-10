package cumt.zongzuo.community.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.dto.ArticleDTO;
import cumt.zongzuo.community.entity.*;
import cumt.zongzuo.community.mapper.*;
import cumt.zongzuo.community.service.ArticleService;
import cumt.zongzuo.community.service.UserService;
import cumt.zongzuo.community.security.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightParameters;
import cumt.zongzuo.community.document.ArticleDoc;
import cumt.zongzuo.community.article.service.ArticleMutationFacade;
import java.util.Arrays;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import cumt.zongzuo.community.document.ArticleDoc;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
    private FollowMapper followMapper;

    // 【新增】注入 Elasticsearch 高级操作模板
    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private ArticleMutationFacade articleMutationFacade;

    // Redis Key 定义
    private static final String ARTICLE_DETAIL_CACHE_PREFIX = "article:detail:";
    private static final String ARTICLE_VIEW_COUNT_KEY = "article:view:count:";
    public static final String ARTICLE_VIEW_DIRTY_SET = "article:view:dirty:set";
    private static final String HOT_RANK_CACHE_KEY = "hot:article:rank:7days";

    // --------------------------------------------------------------------------------
    // 1. 发布/保存文章 (含机器审核逻辑)
    // --------------------------------------------------------------------------------
    @Override
    public Long publishOrSave(ArticleDTO dto, boolean isPublish, Long userId) {
        long articleId = articleMutationFacade.publishOrSave(dto, isPublish, userId);
        stringRedisTemplate.delete(ARTICLE_DETAIL_CACHE_PREFIX + articleId);
        return articleId;
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
        // 1. 如果搜索关键字为空，降级走普通的 MySQL 查询最新文章
        if (StrUtil.isBlank(keyword)) {
            Page<Article> pageInfo = new Page<>(page, size);
            QueryWrapper<Article> query = new QueryWrapper<>();
            query.eq("status", 1).eq("is_deleted", 0);
            query.orderByDesc("create_time");
            Page<Article> result = page(pageInfo, query);
            fillArticleAuthors(result.getRecords());
            result.getRecords().forEach(this::fillArticleTags);
            return result;
        }

        // ================= 【核心：Elasticsearch 高亮搜索】 =================

        // 2. 构建查询条件：匹配标题、摘要或正文 (只要有一个字段包含关键字即可)
        Criteria criteria = new Criteria("title").matches(keyword)
                .or(new Criteria("summary").matches(keyword))
                .or(new Criteria("content").matches(keyword));

        // 3. 构建高亮配置：给匹配到的关键字加上前端红色的标签
        HighlightParameters parameters = HighlightParameters.builder()
                .withPreTags("<span style='color:red; font-weight:bold;'>")
                .withPostTags("</span>")
                .build();

        Highlight highlight = new Highlight(parameters, Arrays.asList(
                new HighlightField("title"),
                new HighlightField("summary"),
                new HighlightField("content")
        ));
        HighlightQuery highlightQuery = new HighlightQuery(highlight, ArticleDoc.class);

        // 4. 组装完整查询请求
        CriteriaQuery query = new CriteriaQuery(criteria);
        // 注意：ES 的分页是从 0 开始的，而前端传过来是从 1 开始的，所以要 -1
        query.setPageable(PageRequest.of(page - 1, size));
        query.setHighlightQuery(highlightQuery);

        // 5. 执行搜索
        SearchHits<ArticleDoc> searchHits = elasticsearchOperations.search(query, ArticleDoc.class);

        // 6. 将 ES 返回的文档，转换为前端需要的 Article 对象
        List<Article> articleList = new ArrayList<>();
        for (SearchHit<ArticleDoc> hit : searchHits) {
            ArticleDoc doc = hit.getContent();
            Article article = new Article();

            // 拷贝基础属性
            article.setId(doc.getId());
            article.setAuthorId(doc.getAuthorId());
            article.setViewCount(doc.getViewCount());
            article.setLikeCount(doc.getLikeCount());
            article.setCommentCount(doc.getCommentCount());
            article.setCollectCount(doc.getCollectCount());
            article.setCreateTime(doc.getCreateTime());
            article.setCover(doc.getCover());

            // 【关键】替换高亮文本：如果高亮结果中有值，就用带红色标签的文本，否则用原文本
            List<String> titleHighlights = hit.getHighlightField("title");
            article.setTitle(titleHighlights.isEmpty() ? doc.getTitle() : titleHighlights.get(0));

            List<String> summaryHighlights = hit.getHighlightField("summary");
            article.setSummary(summaryHighlights.isEmpty() ? doc.getSummary() : summaryHighlights.get(0));

            List<String> contentHighlights = hit.getHighlightField("content");
            article.setContent(contentHighlights.isEmpty() ? doc.getContent() : contentHighlights.get(0));

            articleList.add(article);
        }

        // 7. 填充作者信息和标签 (复用你原本写好的现成方法！)
        fillArticleAuthors(articleList);
        articleList.forEach(this::fillArticleTags);

        // 8. 重新包装成 MyBatis-Plus 的 Page 对象返回给 Controller
        Page<Article> resultPage = new Page<>(page, size);
        resultPage.setRecords(articleList);
        resultPage.setTotal(searchHits.getTotalHits()); // 填入 ES 查出的总记录数

        return resultPage;
    }

    // --------------------------------------------------------------------------------
    // 4. 管理员审核与回收站
    // --------------------------------------------------------------------------------

    @Override
    public Page<Article> getPendingArticles(int page, int size) {
        Page<Article> pageInfo = new Page<>(page, size);
        QueryWrapper<Article> query = new QueryWrapper<>();
        query.eq("status", 2)
                .eq("is_deleted", 0)
                .orderByAsc("create_time")
                .orderByAsc("id");
        Page<Article> result = page(pageInfo, query);
        if (result.getRecords() != null) {
            for (Article a : result.getRecords()) {
                fillArticleAuthorInfo(a);
            }
        }
        return result;
    }

    @Override
    public void auditArticle(Long articleId, boolean pass, String reason) {
        articleMutationFacade.assertArticleWritesAllowed();
        articleMutationFacade.auditLegacyArticle(articleId, pass, reason, CurrentUser.id());
        stringRedisTemplate.delete(ARTICLE_DETAIL_CACHE_PREFIX + articleId);
    }

    @Override
    public void moveToRecycleBin(Long articleId, Long userId) {
        articleMutationFacade.recycle(articleId, userId);
        stringRedisTemplate.delete(ARTICLE_DETAIL_CACHE_PREFIX + articleId);
    }

    @Override
    public void deleteArticle(Long articleId, Long userId) {
        moveToRecycleBin(articleId, userId);
    }

    @Override
    public void restoreArticle(Long articleId, Long userId) {
        articleMutationFacade.restore(articleId, userId);
        stringRedisTemplate.delete(ARTICLE_DETAIL_CACHE_PREFIX + articleId);
    }

    @Override
    public void deletePermanently(Long articleId, Long userId) {
        articleMutationFacade.purge(articleId, userId);
        stringRedisTemplate.delete(ARTICLE_DETAIL_CACHE_PREFIX + articleId);
    }

    @Override
    public List<Article> getRecycleBin(Long userId) {
        QueryWrapper<Article> wrapper = new QueryWrapper<>();
        wrapper.eq("author_id", userId);
        wrapper.eq("is_deleted", 1);
        wrapper.and(nested -> nested.isNull("visibility_state").or().ne("visibility_state", "PURGED"));
        wrapper.orderByDesc("delete_time");
        return list(wrapper);
    }

    @Override
    public void cleanExpiredArticles() {
        articleMutationFacade.cleanExpiredArticles(LocalDateTime.now().minusDays(7), 1000);
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
        return CurrentUser.idOrNull();
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

    @Override
    public List<Article> getSimilarArticles(Long articleId, int size) {
        // 1. 确认原文章是否存在
        Article targetArticle = getById(articleId);
        if (targetArticle == null || targetArticle.getIsDeleted() == 1) {
            return new ArrayList<>();
        }

        // 2. 构建原生 ES 的 More Like This 查询 JSON 语句
        // 告诉 ES：我要在 title(标题), summary(摘要), content(正文) 中找相似的
        // like: [{"_index": "article", "_id": "文章ID"}] 表示以这篇文章为基准
        // min_term_freq: 1 表示只要词出现过 1 次就参与计算
        String mltQueryJson = String.format(
                "{\"more_like_this\": {" +
                        "\"fields\": [\"title\", \"summary\", \"content\"]," +
                        "\"like\": [{\"_index\": \"article\", \"_id\": \"%s\"}]," +
                        "\"min_term_freq\": 1," +
                        "\"max_query_terms\": 25" +
                        "}}", articleId);

        // 3. 封装为 Spring Data ES 的 StringQuery
        StringQuery stringQuery = new StringQuery(mltQueryJson);
        // 我们多查几条，因为可能会把文章自己给查出来，需要在代码里剔除
        stringQuery.setPageable(PageRequest.of(0, size + 1));

        // 4. 执行智能相似度搜索
        SearchHits<ArticleDoc> searchHits = elasticsearchOperations.search(stringQuery, ArticleDoc.class);

        // 5. 将结果转换为前端需要的 Article 实体
        List<Article> resultList = new ArrayList<>();
        for (SearchHit<ArticleDoc> hit : searchHits) {
            ArticleDoc doc = hit.getContent();

            // 排除当前正在看的这篇文章自己
            if (doc.getId().equals(articleId)) {
                continue;
            }

            Article article = new Article();
            article.setId(doc.getId());
            article.setTitle(doc.getTitle());
            article.setSummary(doc.getSummary());
            article.setCover(doc.getCover());
            article.setViewCount(doc.getViewCount());
            article.setAuthorId(doc.getAuthorId());
            article.setCreateTime(doc.getCreateTime());

            resultList.add(article);

            // 达到需要的推荐数量就停止
            if (resultList.size() >= size) {
                break;
            }
        }

        // 6. 填充作者信息，方便前端展示头像和名字
        fillArticleAuthors(resultList);

        return resultList;
    }
}
