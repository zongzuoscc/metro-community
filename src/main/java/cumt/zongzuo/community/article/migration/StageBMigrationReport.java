package cumt.zongzuo.community.article.migration;

import java.time.LocalDateTime;
import java.util.List;

public record StageBMigrationReport(
        boolean passed,
        LocalDateTime databaseStartedAt,
        LocalDateTime databaseFinishedAt,
        String startFingerprint,
        String endFingerprint,
        long articleCount,
        long draftCount,
        long revisionOneCount,
        long revisionCount,
        long moderationJobCount,
        long unresolvedIssueArticleCount,
        long expectedPublicDocumentCount,
        long actualPublicDocumentCount,
        int mysqlPages,
        int elasticsearchPages,
        int maximumElasticsearchLookupBatchSize,
        long mismatchCount,
        List<StageBMigrationMismatch> mismatches) {

    public StageBMigrationReport {
        mismatches = List.copyOf(mismatches);
    }
}
