package cumt.zongzuo.community.event.domain;

/** Stable event names and their single Rabbit routing key. */
public enum DomainEventType {
    // 兼容旧文章写路径的缓存失效事件；不触发审核、通知或索引重建。
    ARTICLE_DETAIL_CACHE_INVALIDATED("article.detail.cache.invalidated"),
    ARTICLE_REVISION_SUBMITTED("article.revision.submitted"),
    ARTICLE_REVISION_PUBLISHED("article.revision.published"),
    ARTICLE_REVISION_REJECTED("article.revision.rejected"),
    ARTICLE_REVISION_SUPERSEDED("article.revision.superseded"),
    ARTICLE_UNPUBLISHED("article.unpublished"),
    ARTICLE_DELETED("article.deleted"),
    ARTICLE_CHUNK_REINDEX_REQUESTED("article.chunk.reindex.requested"),
    AGENT_TURN_REQUESTED("agent.turn.requested"),
    MEMORY_EXTRACTION_REQUESTED("memory.extraction.requested"),
    MEMORY_VERSION_ACTIVATED("memory.version.activated"),
    MEMORY_PAUSED("memory.paused"),
    MEMORY_EXPIRED("memory.expired"),
    MEMORY_DELETED("memory.deleted"),
    EPISODE_SEALED("episode.sealed");

    private final String routingKey;

    DomainEventType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
