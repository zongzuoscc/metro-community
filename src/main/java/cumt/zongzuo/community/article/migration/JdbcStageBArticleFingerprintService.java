package cumt.zongzuo.community.article.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
public final class JdbcStageBArticleFingerprintService implements StageBArticleFingerprintService {

    private final JdbcTemplate jdbc;
    private final StageBMigrationProperties properties;

    public JdbcStageBArticleFingerprintService(JdbcTemplate jdbc, StageBMigrationProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public String fingerprint() {
        int pageSize = properties.getVerificationPageSize();
        if (pageSize < 1 || pageSize > 10_000) {
            throw new IllegalStateException("verification page size must be 1..10000");
        }
        MessageDigest digest = sha256();
        fingerprintTable(digest, "article", "id", """
                id,title,summary,content,cover,author_id,status,is_deleted,update_time,
                latest_revision_id,pending_revision_id,published_revision_id,visibility_state,
                review_state,lifecycle_epoch,lock_version
                """, pageSize);
        fingerprintTable(digest, "article_draft", "article_id", """
                article_id,user_id,draft_version,title,summary,body_markdown,body_plain,cover,
                tags_json,content_hash,created_at,updated_at,lock_version
                """, pageSize);
        fingerprintTable(digest, "article_revision", "id", """
                id,article_id,revision_no,title,summary,body_markdown,body_plain,cover,tags_json,
                content_hash,source_draft_version,created_by,created_at
                """, pageSize);
        fingerprintTable(digest, "article_moderation_job", "id", """
                id,article_id,revision_id,content_hash,state,model_decision,risk_score,
                policy_hits_json,attempt_count,next_attempt_at,lease_owner,lease_until,last_error,
                reviewer_id,review_reason,reviewed_at,created_at,updated_at,lock_version
                """, pageSize);
        fingerprintTable(digest, "article_moderation_attempt", "id", """
                id,job_id,attempt_no,provider,model,prompt_version,input_hash,
                structured_output_json,latency_ms,token_usage_json,finish_reason,error_code,created_at
                """, pageSize);
        fingerprintTable(digest, "article_revision_migration_issue", "id", """
                id,article_id,issue_code,observed_hash,details_json,detected_at,
                resolved_at,resolution_note
                """, pageSize);
        fingerprintArticleTags(digest, pageSize);
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private void fingerprintArticleTags(MessageDigest digest, int pageSize) {
        digest.update("article_tag_with_exact_name\n".getBytes(StandardCharsets.UTF_8));
        long cursor = 0;
        while (true) {
            List<FingerprintRow> rows = jdbc.query("""
                            SELECT at.id,at.article_id,at.tag_id,t.name
                            FROM article_tag at
                            LEFT JOIN tag t ON t.id=at.tag_id
                            WHERE at.id>?
                            ORDER BY at.id
                            LIMIT ?
                            """,
                    (rs, rowNum) -> fingerprintRow(rs, "id", 4), cursor, pageSize);
            if (rows.isEmpty()) {
                return;
            }
            for (FingerprintRow row : rows) {
                cursor = row.id();
                for (String value : row.values()) {
                    updateDigest(digest, value);
                }
            }
            if (rows.size() < pageSize) {
                return;
            }
        }
    }

    private void fingerprintTable(MessageDigest digest, String table, String idColumn,
                                  String columns, int pageSize) {
        digest.update((table + "\n").getBytes(StandardCharsets.UTF_8));
        int columnCount = columns.split(",").length;
        long cursor = 0;
        while (true) {
            List<FingerprintRow> rows = jdbc.query("SELECT " + columns + " FROM " + table
                            + " WHERE " + idColumn + ">? ORDER BY " + idColumn + " LIMIT ?",
                    (rs, rowNum) -> fingerprintRow(rs, idColumn, columnCount), cursor, pageSize);
            if (rows.isEmpty()) {
                return;
            }
            for (FingerprintRow row : rows) {
                cursor = row.id();
                for (String value : row.values()) {
                    updateDigest(digest, value);
                }
            }
            if (rows.size() < pageSize) {
                return;
            }
        }
    }

    private static FingerprintRow fingerprintRow(ResultSet resultSet, String idColumn, int columnCount)
            throws SQLException {
        ArrayList<String> values = new ArrayList<>(columnCount);
        for (int index = 1; index <= columnCount; index++) {
            values.add(resultSet.getString(index));
        }
        return new FingerprintRow(resultSet.getLong(idColumn),
                java.util.Collections.unmodifiableList(values));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        if (value == null) {
            digest.update("-1:\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
        digest.update((byte) '\n');
    }

    private record FingerprintRow(long id, List<String> values) {
    }
}
