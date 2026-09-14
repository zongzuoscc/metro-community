package cumt.zongzuo.community.article;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.article.service.PublishedArticleReadService;
import cumt.zongzuo.community.article.service.ArticleDetailCache;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.service.impl.ArticleServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证详情命中时真正绕过数据库，而不只是减少作者信息的补齐。 */
class ArticleDetailCacheAsideTest {
    @Test
    @SuppressWarnings("unchecked")
    void cachedPublicBodyDoesNotReadMysql() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        Article cached = new Article();
        cached.setId(101L);
        cached.setStatus(1);
        cached.setIsDeleted(0);
        cached.setContent("缓存中的公开正文");
        cached.setViewCount(10);
        when(values.get("article:detail:public:v3:legacy:101"))
                .thenReturn(json.writeValueAsString(cached));
        when(redis.hasKey(anyString())).thenReturn(true);
        when(values.increment(anyString())).thenReturn(11L);
        PublishedArticleReadService source = mock(PublishedArticleReadService.class);
        // 数据库不可用时，已经命中的公开详情仍应正常返回。
        when(source.findById(anyLong())).thenThrow(new AssertionError("缓存命中不应查询 MySQL"));
        ArticleServiceImpl service = new ArticleServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "objectMapper", json);
        ReflectionTestUtils.setField(service, "publishedArticleReadService", source);
        ReflectionTestUtils.setField(service, "articleDetailCache",
                new ArticleDetailCache(redis, json, java.time.Duration.ofSeconds(30)));

        assertThat(service.getDetail(101L).getContent()).isEqualTo("缓存中的公开正文");
        verify(source, never()).findById(anyLong());
    }
}
