package cumt.zongzuo.community.article.migration;

public record StageBMigrationExecution(
        StageBMigrationAction action,
        MigrationRunResult backfill,
        StageBMigrationReport report) {
}
