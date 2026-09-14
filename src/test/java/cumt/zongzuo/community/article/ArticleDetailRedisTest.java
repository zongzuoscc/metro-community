package cumt.zongzuo.community.article;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.article.service.ArticleDetailCache;
import cumt.zongzuo.community.entity.Article;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** 使用项目专属 Redis 的随机测试 Key，禁止 FLUSHDB，验证真实 Lua 和并发交错。 */
@EnabledIfSystemProperty(named = "community.cache-test.redis-port", matches = "[0-9]+")
class ArticleDetailRedisTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private ArticleDetailCache cache;
    private long id;

    @BeforeEach
    void connect() {
        var configuration = new RedisStandaloneConfiguration("127.0.0.1",
                Integer.parseInt(System.getProperty("community.cache-test.redis-port")));
        String password = System.getenv("COMMUNITY_CACHE_TEST_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            configuration.setPassword(password);
        }
        connection = new LettuceConnectionFactory(configuration);
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        cache = new ArticleDetailCache(redis, new ObjectMapper().findAndRegisterModules(), Duration.ofSeconds(30));
        id = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
    }

    @AfterEach
    void cleanupOnlyOwnKeys() {
        try {
            cache.evict(id);
        } finally {
            connection.destroy();
        }
    }

    @Test
    void invalidationDuringLoadPreventsOldBodyBeingFilledBack() {
        Article old = cache.getOrLoad(id, false, () -> {
            cache.evict(id);
            return article("旧正文");
        });
        assertThat(old.getContent()).isEqualTo("旧正文");
        assertThat(cache.getOrLoad(id, false, () -> article("新正文")).getContent()).isEqualTo("新正文");
        assertThat(cache.getOrLoad(id, false, () -> { throw new AssertionError("命中不回源"); })
                .getContent()).isEqualTo("新正文");
    }

    @Test
    void invalidationAlsoCancelsNegativeCacheFill() {
        assertThat(cache.getOrLoad(id, true, () -> { cache.evict(id); return null; })).isNull();
        assertThat(cache.getOrLoad(id, true, () -> article("刚发布"))).isNotNull();
    }

    @Test
    void negativeCacheAvoidsRepeatedMissingArticleReadsAndEvictionReleasesIt() {
        AtomicInteger queries = new AtomicInteger();
        assertThat(cache.getOrLoad(id, false, () -> { queries.incrementAndGet(); return null; })).isNull();
        assertThat(cache.getOrLoad(id, false, () -> { queries.incrementAndGet(); return null; })).isNull();
        assertThat(queries).hasValue(1);
        cache.evict(id);
        assertThat(cache.getOrLoad(id, false, () -> article("已发布"))).isNotNull();
    }

    @Test
    void loaderFailureDoesNotLeaveAReusableBadSnapshot() {
        assertThatThrownBy(() -> cache.getOrLoad(id, false, () -> { throw new IllegalStateException("数据库失败"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cache.getOrLoad(id, false, () -> article("恢复")).getContent()).isEqualTo("恢复");
    }

    @Test
    void expiredCacheReloadsInsteadOfServingIndefinitely() throws Exception {
        cache.getOrLoad(id, false, () -> article("旧正文"));
        redis.expire("article:detail:public:v3:legacy:" + id, Duration.ofMillis(30));
        Thread.sleep(80);
        assertThat(cache.getOrLoad(id, false, () -> article("新正文")).getContent()).isEqualTo("新正文");
    }

    @Test
    void thousandHotReadsDoNotInvokeTheDatabaseLoader() throws Exception {
        cache.getOrLoad(id, false, () -> article("热点正文"));
        AtomicInteger queries = new AtomicInteger();
        long started = System.nanoTime();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Article>> results = java.util.stream.IntStream.range(0, 1000)
                    .mapToObj(index -> workers.submit(() -> {
                        start.await();
                        return cache.getOrLoad(id, false, () -> {
                            queries.incrementAndGet();
                            return article("不应回源");
                        });
                    })).toList();
            start.countDown();
            for (Future<Article> result : results) {
                assertThat(result.get(20, TimeUnit.SECONDS).getContent()).isEqualTo("热点正文");
            }
        }
        assertThat(queries).hasValue(0);
        System.out.printf("缓存组件并发验证：1000/1000 成功，回源=%d，总耗时=%.3f 秒%n",
                queries.get(), (System.nanoTime() - started) / 1_000_000_000.0);
    }

    private Article article(String content) {
        Article value = new Article();
        value.setId(id);
        value.setStatus(1);
        value.setIsDeleted(0);
        value.setPublishedRevisionId(100L);
        value.setContent(content);
        return value;
    }
}
