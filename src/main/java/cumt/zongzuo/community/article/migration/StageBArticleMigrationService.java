package cumt.zongzuo.community.article.migration;

public interface StageBArticleMigrationService {

    MigrationBatchResult backfillAfter(long afterArticleId, int limit);

    MigrationRunResult backfillAll(int batchSize);
}
