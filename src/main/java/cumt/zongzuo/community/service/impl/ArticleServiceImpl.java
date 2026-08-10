package cumt.zongzuo.community.service.impl;

import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
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
import cumt.zongzuo.community.document.ArticleDoc;
import cumt.zongzuo.community.article.service.ArticleMutationFacade;
import cumt.zongzuo.community.article.service.AuthorArticleReadService;
import cumt.zongzuo.community.article.service.PublishedArticleReadService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.data.elasticsearch.core.query.StringQuery;
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
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private RestClient elasticsearchRestClient;

    @Autowired
    private ArticleMutationFacade articleMutationFacade;

    @Autowired
    private PublishedArticleReadService publishedArticleReadService;

    @Autowired
    private AuthorArticleReadService authorArticleReadService;

    // Redis Key 定义
    private static final String ARTICLE_DETAIL_CACHE_PREFIX = "article:detail:";
    private static final String ARTICLE_VIEW_COUNT_KEY = "article:view:count:";
    public static final String ARTICLE_VIEW_DIRTY_SET = "article:view:dirty:set";
    private static final String HOT_RANK_CACHE_KEY = "hot:article:rank:7days";
    private static final String ARTICLE_SEARCH_INDEX = "article";
    private static final String SEARCH_PIT_KEEP_ALIVE = "1m";
    private static final int SEARCH_CANDIDATE_BATCH_SIZE = 100;
    private static final int SEARCH_CANDIDATE_HARD_LIMIT = 1_000;

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
        // MySQL current visibility and pointer identity are authoritative. Resolve them
        // before touching Redis so an old cache entry can never bypass unpublish/delete.
        Article current = publishedArticleReadService.findById(id);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文章不存在或未发布");
        }
        String cacheKey = articleDetailCacheKey(current);
        String json = stringRedisTemplate.opsForValue().get(cacheKey);

        Article article = null;
        if (StrUtil.isNotBlank(json)) {
            try {
                article = objectMapper.readValue(json, Article.class);
            } catch (Exception e) {
                log.error("文章详情缓存解析失败", e);
            }
        }

        // Cache misses use the already-authorized current snapshot.
        if (article == null) {
            article = current;

            // 填充作者信息
            fillArticleAuthorInfo(article);
            if (article.getTagList() == null) {
                fillArticleTags(article);
            }

            // 3. 写入 Redis (过期时间 1 小时)
            try {
                String cacheValue = objectMapper.writeValueAsString(article);
                stringRedisTemplate.opsForValue().set(cacheKey, cacheValue, 1, TimeUnit.HOURS);
            } catch (Exception e) {
                log.error("文章详情写入缓存失败", e);
            }
        }

        // 浏览量处理 (Redis 实时计数)
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
        List<Article> list = publishedArticleReadService.findHot(10);
        fillArticleAuthors(list);
        return list;
    }

    @Override
    public List<Article> getFeedArticles(String lastCreateTime) {
        FeedPosition position = parseFeedPosition(lastCreateTime);
        List<Article> list = publishedArticleReadService.findChronological(
                position == null ? null : position.createTime(),
                position == null ? null : position.articleId(), 10);
        fillArticleAuthors(list);
        return list;
    }

    @Override
    public List<Article> getHotRank() {
        return publishedArticleReadService.findHotRank(10);
    }

    @Override
    public Page<Article> getUserArticles(Long userId, int pageNo, int pageSize) {
        Page<Article> page = publishedArticleReadService.findByAuthor(userId, pageNo, pageSize);
        fillArticleAuthors(page.getRecords());
        return page;
    }

    @Override
    public Page<Article> getFollowArticles(Long userId, int pageNo, int pageSize) {
        Page<Article> page = publishedArticleReadService.findByFollowing(userId, pageNo, pageSize);
        fillArticleAuthors(page.getRecords());
        return page;
    }

    @Override
    public Page<Article> searchArticles(String keyword, int page, int size) {
        // 1. 如果搜索关键字为空，降级走普通的 MySQL 查询最新文章
        if (StrUtil.isBlank(keyword)) {
            Page<Article> result = publishedArticleReadService.findPage(page, size);
            fillArticleAuthors(result.getRecords());
            result.getRecords().stream()
                    .filter(article -> article.getTagList() == null)
                    .forEach(this::fillArticleTags);
            return result;
        }

        if (page < 1 || size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数不合法");
        }

        // Elasticsearch only ranks candidate ids. Authorization and pagination
        // happen after current MySQL rehydration, so stale/private hits cannot
        // consume a visible slot or make later public results unreachable.
        AuthorizedSearchRecall recall = recallAuthorizedSearch(keyword, page, size);
        List<Article> articleList = recall.records();

        // Fill only presentation metadata after authorization.
        fillArticleAuthors(articleList);
        articleList.stream().filter(article -> article.getTagList() == null).forEach(this::fillArticleTags);

        // 8. 重新包装成 MyBatis-Plus 的 Page 对象返回给 Controller
        Page<Article> resultPage = new Page<>(page, size);
        resultPage.setRecords(articleList);
        resultPage.setTotal(recall.authorizedTotal());

        return resultPage;
    }

    private AuthorizedSearchRecall recallAuthorizedSearch(String keyword, int page, int size) {
        long authorizedOffset = (long) (page - 1) * size;
        long desiredCount = authorizedOffset + size;
        List<Article> authorized = new ArrayList<>();
        Set<Long> seenCandidateIds = new HashSet<>();
        List<FieldValue> searchAfter = List.of();
        String pitId = null;
        try {
            pitId = openSearchPointInTime();
            long rawTotal = -1L;
            while (true) {
                String activePitId = pitId;
                SearchRequest.Builder request = new SearchRequest.Builder()
                        .pit(pit -> pit.id(activePitId)
                                .keepAlive(keepAlive -> keepAlive.time(SEARCH_PIT_KEEP_ALIVE)))
                        .size(SEARCH_CANDIDATE_BATCH_SIZE)
                        .source(source -> source.fetch(false))
                        .trackTotalHits(track -> track.enabled(true))
                        .query(query -> query.bool(bool -> bool
                                .must(must -> must.multiMatch(multiMatch -> multiMatch
                                        .query(keyword)
                                        .fields("title", "summary", "content")))
                                .mustNot(mustNot -> mustNot.term(term -> term
                                        .field("projectionTombstone").value(true)))))
                        .sort(sort -> sort.score(score -> score.order(SortOrder.Desc)))
                        .sort(sort -> sort.field(field -> field.field("_shard_doc").order(SortOrder.Asc)));
                if (!searchAfter.isEmpty()) {
                    request.searchAfter(searchAfter);
                }

                SearchResponse<Void> response = elasticsearchClient.search(request.build());
                if (StrUtil.isNotBlank(response.pitId())) {
                    pitId = response.pitId();
                }
                if (response.timedOut() || response.shards().failed().longValue() != 0L) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "ARTICLE_SEARCH_INCOMPLETE");
                }
                if (rawTotal < 0L) {
                    rawTotal = response.hits().total() == null ? 0L : response.hits().total().value();
                    if (rawTotal > SEARCH_CANDIDATE_HARD_LIMIT) {
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                "SEARCH_CANDIDATE_WINDOW_INCOMPLETE");
                    }
                }

                List<Hit<Void>> hits = response.hits().hits();
                if (hits.isEmpty()) {
                    break;
                }
                List<Long> candidateIds = hits.stream()
                        .map(Hit::id)
                        .map(this::parseSearchCandidateId)
                        .filter(java.util.Objects::nonNull)
                        .filter(seenCandidateIds::add)
                        .toList();
                Map<Long, Article> authorizedBatch = publishedArticleReadService.findByIds(candidateIds).stream()
                        .collect(Collectors.toMap(Article::getId, value -> value, (first, ignored) -> first));
                for (Long candidateId : candidateIds) {
                    Article article = authorizedBatch.get(candidateId);
                    if (article != null) {
                        authorized.add(article);
                    }
                }

                searchAfter = hits.getLast().sort();
                if (hits.size() < SEARCH_CANDIDATE_BATCH_SIZE) {
                    break;
                }
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ARTICLE_SEARCH_UNAVAILABLE", exception);
        } finally {
            closeSearchPointInTime(pitId);
        }

        int fromIndex = (int) Math.min(authorizedOffset, authorized.size());
        int toIndex = (int) Math.min(desiredCount, authorized.size());
        List<Article> records = List.copyOf(authorized.subList(fromIndex, toIndex));
        return new AuthorizedSearchRecall(records, authorized.size());
    }

    private Long parseSearchCandidateId(String candidateId) {
        try {
            return Long.valueOf(candidateId);
        } catch (RuntimeException invalidId) {
            return null;
        }
    }

    private void closeSearchPointInTime(String pitId) {
        if (StrUtil.isBlank(pitId)) {
            return;
        }
        try {
            Request request = new Request("DELETE", "/_pit");
            request.setJsonEntity(objectMapper.createObjectNode().put("id", pitId).toString());
            Response response = elasticsearchRestClient.performRequest(request);
            if (!objectMapper.readTree(EntityUtils.toString(response.getEntity()))
                    .path("succeeded").asBoolean(false)) {
                log.warn("Elasticsearch search PIT close returned succeeded=false");
            }
        } catch (Exception exception) {
            log.warn("Elasticsearch search PIT close failed", exception);
        }
    }

    private String openSearchPointInTime() throws Exception {
        Request request = new Request("POST", "/" + ARTICLE_SEARCH_INDEX + "/_pit");
        request.addParameter("keep_alive", SEARCH_PIT_KEEP_ALIVE);
        Response response = elasticsearchRestClient.performRequest(request);
        String pitId = objectMapper.readTree(EntityUtils.toString(response.getEntity()))
                .path("id").asText();
        if (StrUtil.isBlank(pitId)) {
            throw new IllegalStateException("Elasticsearch PIT open returned no id");
        }
        return pitId;
    }

    // --------------------------------------------------------------------------------
    // 4. 管理员审核与回收站
    // --------------------------------------------------------------------------------

    @Override
    public Page<Article> getPendingArticles(int page, int size) {
        Page<Article> result = authorArticleReadService.findPending(page, size);
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
        return authorArticleReadService.findRecycleBin(userId);
    }

    @Override
    public void cleanExpiredArticles() {
        articleMutationFacade.cleanExpiredArticles(LocalDateTime.now().minusDays(7), 1000);
    }

    @Override
    public List<Article> getMyDrafts(Long userId) {
        return authorArticleReadService.findDrafts(userId);
    }

    @Override
    public Article getArticleForEdit(Long articleId, Long userId) {
        return authorArticleReadService.findForEdit(articleId, userId);
    }

    @Override
    public Long getDraftCount(Long userId) {
        return authorArticleReadService.countDrafts(userId);
    }

    @Override
    public List<Article> getHotArticles7Days() {
        String json = stringRedisTemplate.opsForValue().get(HOT_RANK_CACHE_KEY);
        if (StrUtil.isNotBlank(json)) {
            try {
                List<Long> cachedIds = objectMapper.readValue(json, new TypeReference<List<Long>>() { });
                return hydrateHotRankIds(cachedIds);
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
            String json = objectMapper.writeValueAsString(
                    hotArticles.stream().map(Article::getId).toList());
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
        List<Article> list = publishedArticleReadService.findHotSince(sevenDaysAgo, 10);
        fillArticleAuthors(list);
        list.stream().filter(article -> article.getTagList() == null).forEach(this::fillArticleTags);
        return list;
    }

    private List<Article> hydrateHotRankIds(List<Long> cachedIds) {
        if (cachedIds == null || cachedIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Article> publishedById = publishedArticleReadService.findByIds(cachedIds).stream()
                .collect(Collectors.toMap(Article::getId, article -> article, (first, ignored) -> first));
        List<Article> articles = cachedIds.stream()
                .map(publishedById::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        fillArticleAuthors(articles);
        articles.stream().filter(article -> article.getTagList() == null).forEach(this::fillArticleTags);
        return articles;
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

    private String articleDetailCacheKey(Article article) {
        if (publishedArticleReadService.pointerReadsEnabled()) {
            return ARTICLE_DETAIL_CACHE_PREFIX + "v2:" + article.getId() + ":"
                    + article.getPublishedRevisionId() + ":" + article.getContentHash();
        }
        return ARTICLE_DETAIL_CACHE_PREFIX + "legacy:" + article.getId() + ":"
                + String.valueOf(article.getUpdateTime());
    }

    private FeedPosition parseFeedPosition(String cursor) {
        if (StrUtil.isBlank(cursor)) {
            return null;
        }
        try {
            int separator = cursor.lastIndexOf('|');
            if (separator > 0) {
                return new FeedPosition(LocalDateTime.parse(cursor.substring(0, separator)),
                        Long.parseLong(cursor.substring(separator + 1)));
            }
            // Legacy clients only sent the timestamp and expected strict `<` semantics.
            return new FeedPosition(LocalDateTime.parse(cursor), Long.MIN_VALUE);
        } catch (RuntimeException invalidCursor) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ARTICLE_CURSOR");
        }
    }

    private record FeedPosition(LocalDateTime createTime, Long articleId) { }

    private record AuthorizedSearchRecall(List<Article> records, long authorizedTotal) { }

    @Override
    public Page<Article> getMyAllArticles(Long userId, int page, int size) {
        return authorArticleReadService.findAll(userId, page, size);
    }

    @Override
    public List<Article> getSimilarArticles(Long articleId, int size) {
        // The MLT seed itself must still be public in current MySQL truth.
        Article targetArticle = publishedArticleReadService.findById(articleId);
        if (targetArticle == null) {
            return new ArrayList<>();
        }

        // 2. 构建原生 ES 的 More Like This 查询 JSON 语句
        // 告诉 ES：我要在 title(标题), summary(摘要), content(正文) 中找相似的
        // like: [{"_index": "article", "_id": "文章ID"}] 表示以这篇文章为基准
        // min_term_freq: 1 表示只要词出现过 1 次就参与计算
        String mltQueryJson = String.format(
                "{\"bool\":{\"must\":[{\"more_like_this\": {" +
                        "\"fields\": [\"title\", \"summary\", \"content\"]," +
                        "\"like\": [{\"_index\": \"article\", \"_id\": \"%s\"}]," +
                        "\"min_term_freq\": 1," +
                        "\"max_query_terms\": 25" +
                        "}}],\"must_not\":[{\"term\":{\"projectionTombstone\":true}}]}}",
                articleId);

        // 3. 封装为 Spring Data ES 的 StringQuery
        StringQuery stringQuery = new StringQuery(mltQueryJson);
        // 我们多查几条，因为可能会把文章自己给查出来，需要在代码里剔除
        stringQuery.setPageable(PageRequest.of(0, size + 1));

        // 4. 执行智能相似度搜索
        SearchHits<ArticleDoc> searchHits = elasticsearchOperations.search(stringQuery, ArticleDoc.class);

        // Elasticsearch contributes ordered ids only. Current content and ACL are
        // rehydrated in one MySQL query and stale/missing ids are discarded.
        List<Long> candidateIds = searchHits.stream()
                .map(SearchHit::getContent)
                .filter(java.util.Objects::nonNull)
                .map(ArticleDoc::getId)
                .filter(id -> id != null && !id.equals(articleId))
                .distinct()
                .toList();
        Map<Long, Article> authorizedById = publishedArticleReadService.findByIds(candidateIds).stream()
                .collect(Collectors.toMap(Article::getId, value -> value, (first, ignored) -> first));
        List<Article> resultList = candidateIds.stream()
                .map(authorizedById::get)
                .filter(java.util.Objects::nonNull)
                .limit(size)
                .toList();

        // 6. 填充作者信息，方便前端展示头像和名字
        fillArticleAuthors(resultList);

        return resultList;
    }
}
