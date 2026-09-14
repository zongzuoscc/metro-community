package cumt.zongzuo.community.article.service;

import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.event.domain.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 复用文章事件完成提交后失效与 MQ 补删；DEL 天然幂等，不增加 Inbox/水位表。 */
@Component
public class ArticleDetailCacheInvalidation {
    private static final Logger log = LoggerFactory.getLogger(ArticleDetailCacheInvalidation.class);
    private final ArticleDetailCache cache;

    public ArticleDetailCacheInvalidation(ArticleDetailCache cache) {
        this.cache = cache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(DomainEvent event) {
        try {
            consume(event);
        } catch (RuntimeException unavailable) {
            // 数据库已经提交，不能让删缓存失败伪装成业务回滚；持久消息会再次尝试。
            log.warn("文章缓存立即失效失败，等待 Outbox 补删：articleId={}", event.aggregateId());
        }
    }

    @RabbitListener(id = "articleDetailCacheInvalidationConsumer",
            queues = RabbitConfig.ARTICLE_DETAIL_CACHE_QUEUE)
    public void consume(DomainEvent event) {
        if ("ARTICLE".equals(event.aggregateType())) {
            // 迟到或重复事件最多额外删除一次缓存，不会把旧正文重新写回来。
            // 此处不能吞掉 Redis 异常，否则消息被 ACK 后将失去重试机会。
            cache.evict(event.aggregateId());
        }
    }
}
