package cumt.zongzuo.community.article.migration;

public record MigrationRunResult(
        long lastArticleId,
        long scanned,
        long migrated,
        long issues,
        int batches) {
}
