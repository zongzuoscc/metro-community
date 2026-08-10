package cumt.zongzuo.community.article.migration;

public record MigrationBatchResult(
        long afterArticleId,
        long lastArticleId,
        int scanned,
        int migrated,
        int issues,
        boolean hasMore) {
}
