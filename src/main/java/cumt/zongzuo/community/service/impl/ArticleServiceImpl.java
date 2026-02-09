package cumt.zongzuo.community.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 定义常量
    private static final String ARTICLE_VIEW_COUNT_KEY = "article:view:count:";
    public static final String ARTICLE_VIEW_DIRTY_SET = "article:view:dirty:ids";

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
        Article article = getById(id);
        if (article == null) {
            throw new RuntimeException("文章不存在");
        }

        // 如果文章已删除，且查看者不是作者本人，应该提示不存在 (这里简单起见暂不做作者校验拦截，前端会过滤)
        if (article.getIsDeleted() == 1) {
            // throw new RuntimeException("文章已被删除"); // 可选：严格模式下抛出异常
        }

        // 填充作者信息
        Long authorId = article.getAuthorId();
        if (authorId != null) {
            User author = userMapper.selectById(authorId);
            if (author != null) {
                article.setAuthorName(author.getUsername());
                article.setAuthorAvatar(author.getAvatar());
                article.setAuthorIntro(author.getIntro());

                // 统计文章总数 (status=1 AND is_deleted=0)
                Long articleCount = this.count(new QueryWrapper<Article>()
                        .eq("author_id", authorId)
                        .eq("status", 1)
                        .eq("is_deleted", 0));
                article.setAuthorArticleCount(articleCount);

                // 统计获赞总数
                // 注意：这里建议优化 ArticleMapper.sumLikesByAuthorId 的SQL，加上 status=1 AND is_deleted=0
                Long totalLikes = baseMapper.sumLikesByAuthorId(authorId);
                article.setAuthorTotalLikes(totalLikes);
            } else {
                article.setAuthorName("注销用户");
                article.setAuthorAvatar("https://cube.elemecdn.com/9/c2/f0ee8a3c7c9638a54940382568c9dpng.png");
            }
        }

        // 处理浏览量 (Redis)
        String viewCountKey = ARTICLE_VIEW_COUNT_KEY + id;
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(viewCountKey))) {
            Integer dbViewCount = article.getViewCount();
            String initValue = (dbViewCount == null) ? "0" : dbViewCount.toString();
            stringRedisTemplate.opsForValue().set(viewCountKey, initValue, 24, TimeUnit.HOURS);
        }
        Long newViewCount = stringRedisTemplate.opsForValue().increment(viewCountKey);
        if (newViewCount != null) {
            article.setViewCount(newViewCount.intValue());
        }
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
        if (articles == null || articles.isEmpty()) {
            return;
        }
        Set<Long> userIds = articles.stream().map(Article::getAuthorId).collect(Collectors.toSet());
        List<User> users = userMapper.selectBatchIds(userIds);
        Map<Long, User> userMap = users.stream().collect(Collectors.toMap(User::getId, u -> u));

        for (Article article : articles) {
            User u = userMap.get(article.getAuthorId());
            if (u != null) {
                article.setAuthorName(u.getUsername());
                article.setAuthorAvatar(u.getAvatar());
            } else {
                article.setAuthorName("注销用户");
                article.setAuthorAvatar("https://cube.elemecdn.com/9/c2/f0ee8a3c7c9638a54940382568c9dpng.png");
            }
        }
    }
}