package cumt.zongzuo.community.article.migration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class StageBMigrationReportHasher {

    private StageBMigrationReportHasher() {
    }

    public static String hash(StageBMigrationReport report) {
        if (report == null) {
            throw new IllegalArgumentException("verification report is required");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
        update(digest, report.databaseStartedAt().toString());
        update(digest, report.databaseFinishedAt().toString());
        update(digest, Boolean.toString(report.passed()));
        update(digest, report.startFingerprint());
        update(digest, report.endFingerprint());
        update(digest, Long.toString(report.articleCount()));
        update(digest, Long.toString(report.draftCount()));
        update(digest, Long.toString(report.revisionOneCount()));
        update(digest, Long.toString(report.revisionCount()));
        update(digest, Long.toString(report.moderationJobCount()));
        update(digest, Long.toString(report.unresolvedIssueArticleCount()));
        update(digest, Long.toString(report.expectedPublicDocumentCount()));
        update(digest, Long.toString(report.actualPublicDocumentCount()));
        update(digest, Integer.toString(report.mysqlPages()));
        update(digest, Integer.toString(report.elasticsearchPages()));
        update(digest, Integer.toString(report.maximumElasticsearchLookupBatchSize()));
        update(digest, Long.toString(report.mismatchCount()));
        for (StageBMigrationMismatch mismatch : report.mismatches()) {
            update(digest, mismatch.code());
            update(digest, String.valueOf(mismatch.articleId()));
            update(digest, mismatch.detail());
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
