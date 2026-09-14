package cumt.zongzuo.community.article;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.article.service.ArticleDetailCache;
import cumt.zongzuo.community.article.service.ArticleDetailCacheInvalidation;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Redis 故障的两个边界：提交后不误报业务失败，消费者必须抛错以保留重试。 */
class ArticleDetailCacheInvalidationTest {
    @Test
    void redisFailureDoesNotMasqueradeAsDatabaseRollbackButConsumerMustRetry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.delete(anyCollection())).thenThrow(new RedisConnectionFailureException("测试断连"));
        var handler = new ArticleDetailCacheInvalidation(
                new ArticleDetailCache(redis, new ObjectMapper(), Duration.ofSeconds(30)));
        var event = event("ARTICLE", DomainEventType.ARTICLE_UNPUBLISHED);
        assertThatCode(() -> handler.afterCommit(event)).doesNotThrowAnyException();
        assertThatThrownBy(() -> handler.consume(event)).isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    void chunkEventsDoNotInvalidateUnrelatedArticleDetail() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        var handler = new ArticleDetailCacheInvalidation(
                new ArticleDetailCache(redis, new ObjectMapper(), Duration.ofSeconds(30)));
        handler.consume(event("ARTICLE_CHUNK_SET", DomainEventType.ARTICLE_CHUNK_REINDEX_REQUESTED));
        verify(redis, never()).delete(anyCollection());
    }

    @Test
    void redisReadFailureFallsBackToAuthoritativeLoader() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("测试断连"));
        Article published = new Article();
        published.setContent("数据库公开正文");
        var cache = new ArticleDetailCache(redis, new ObjectMapper(), Duration.ofSeconds(30));
        assertThat(cache.getOrLoad(123L, false, () -> published).getContent()).isEqualTo("数据库公开正文");
    }

    @Test
    void activeTransactionDoesNotReadOrPopulateSharedCache() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        var cache = new ArticleDetailCache(redis, new ObjectMapper(), Duration.ofSeconds(30));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            Article transactionView = new Article();
            assertThat(cache.getOrLoad(123L, false, () -> transactionView)).isSameAs(transactionView);
            verifyNoInteractions(redis);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private DomainEvent event(String aggregateType, DomainEventType type) {
        return new DomainEvent(UUID.randomUUID(), aggregateType, 123L, 1, 0, type, 1,
                new ObjectMapper().createObjectNode().put("articleId", 123L), Instant.now());
    }
}
