package cumt.zongzuo.community.article.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.entity.Article;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公开详情的旁路缓存。命中不回表，只有公开查询返回的快照才允许进入缓存。
 * 普通展示接受短暂陈旧；权限判断、编辑和检索授权仍使用各自的权威查询。
 */
@Component
public class ArticleDetailCache {
    private static final Logger log = LoggerFactory.getLogger(ArticleDetailCache.class);
    private static final String PREFIX = "article:detail:public:v3:";
    private static final String LOADING = "loading:";
    private static final String ABSENT = "absent";
    // 占位与数据共用一个 Key：删除缓存同时取消旧加载者的回填资格。
    // 不能使用无条件 SET，否则更新后的 DEL 会被迟到的旧查询覆盖。
    private static final DefaultRedisScript<Long> FILL = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
                return 1
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    public ArticleDetailCache(StringRedisTemplate redis, ObjectMapper json,
                              @Value("${metro.article.detail-cache.ttl:PT30S}") Duration ttl) {
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("文章详情缓存有效期必须在 0 到 5 分钟之间");
        }
        this.redis = redis;
        // HTTP 使用的 Article 会隐藏 revisionId 等内部字段；缓存必须保留它们，
        // 否则反序列化后丢失版本身份。仅调整私有副本，不改变接口的字段可见性。
        this.json = json.copy();
        this.json.setConfig(json.getSerializationConfig().without(com.fasterxml.jackson.databind.MapperFeature.USE_ANNOTATIONS));
        this.json.setConfig(json.getDeserializationConfig().without(com.fasterxml.jackson.databind.MapperFeature.USE_ANNOTATIONS));
        this.ttl = ttl;
    }

    public Article getOrLoad(long id, boolean revisionMode, Supplier<Article> loader) {
        // 事务内不能把尚未提交的公开状态写入共享缓存，也不能用缓存替代事务读。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return loader.get();
        }
        String key = key(id, revisionMode);
        String token = LOADING + UUID.randomUUID();
        boolean ownsFill;
        try {
            String cached = redis.opsForValue().get(key);
            if (ABSENT.equals(cached)) {
                return null;
            }
            if (cached != null && !cached.startsWith(LOADING)) {
                try {
                    Article article = json.readValue(cached, Article.class);
                    if (article != null && Long.valueOf(id).equals(article.getId())
                            && Integer.valueOf(1).equals(article.getStatus())
                            && Integer.valueOf(0).equals(article.getIsDeleted())
                            && (!revisionMode || article.getPublishedRevisionId() != null)) {
                        return article;
                    }
                } catch (Exception invalidJson) {
                    log.warn("文章详情缓存格式失效，重新加载：articleId={}", id);
                }
                redis.execute(REMOVE, List.of(key), cached);
            }
            ownsFill = Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(key, token, Duration.ofSeconds(5)));
        } catch (RuntimeException unavailable) {
            // Redis 失效只影响加速，不把公开文章查询变成 500；数据库异常仍由上层处理。
            log.warn("文章详情缓存读取失败，回源数据库：articleId={}", id);
            return loader.get();
        }
        Article loaded;
        try {
            loaded = loader.get();
        } catch (RuntimeException | Error failure) {
            if (ownsFill) {
                removeQuietly(key, token);
            }
            throw failure;
        }
        if (ownsFill) {
            try {
                String value = loaded == null ? ABSENT : json.writeValueAsString(loaded);
                long millis = loaded == null ? Math.min(ttl.toMillis(), 3000) : ttl.toMillis();
                redis.execute(FILL, List.of(key), token, value, Long.toString(millis));
            } catch (Exception unavailable) {
                log.warn("文章详情缓存回填失败，不影响本次查询：articleId={}", id);
                removeQuietly(key, token);
            }
        }
        // 并发未取得回填资格的请求仍可回源，但不覆盖其他加载者；这里不持有分布式长锁。
        return loaded;
    }

    /** 同时清除两种读模式，避免切换模式后重新使用旧快照。失败必须让 MQ 重试。 */
    public void evict(long articleId) {
        redis.delete(List.of(key(articleId, false), key(articleId, true)));
    }

    private void removeQuietly(String key, String token) {
        try {
            redis.execute(REMOVE, List.of(key), token);
        } catch (RuntimeException ignored) {
            // 加载占位自身有 5 秒 TTL，不另起后台线程或无限重试。
        }
    }

    private static String key(long id, boolean revisionMode) {
        return PREFIX + (revisionMode ? "revision:" : "legacy:") + id;
    }
}
