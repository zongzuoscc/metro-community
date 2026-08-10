package cumt.zongzuo.community.article.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Narrow JDBC boundary used by the operator migration. Every method is invoked
 * with the JdbcTemplate bound to the runner's single physical MySQL connection.
 */
public final class ArticleMigrationMapper {

    private ArticleMigrationMapper() {
    }

    public static List<LegacyArticleRow> selectBatchForUpdate(
            JdbcTemplate jdbc, long afterArticleId, int limit) {
        return jdbc.query("""
                SELECT id,title,summary,content,cover,author_id,status,is_deleted,
                       latest_revision_id,pending_revision_id,published_revision_id,
                       visibility_state,review_state,lock_version
                FROM article
                WHERE id > ?
                ORDER BY id
                LIMIT ?
                FOR UPDATE
                """, (rs, rowNum) -> new LegacyArticleRow(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("content"),
                rs.getString("cover"),
                rs.getLong("author_id"),
                nullableInteger(rs, "status"),
                nullableInteger(rs, "is_deleted"),
                nullableLong(rs, "latest_revision_id"),
                nullableLong(rs, "pending_revision_id"),
                nullableLong(rs, "published_revision_id"),
                rs.getString("visibility_state"),
                rs.getString("review_state"),
                rs.getLong("lock_version")), afterArticleId, limit);
    }

    public static List<String> selectLegacyTags(JdbcTemplate jdbc, long articleId) {
        return jdbc.queryForList("""
                SELECT t.name
                FROM article_tag at
                JOIN tag t ON t.id=at.tag_id
                WHERE at.article_id=?
                ORDER BY t.name,t.id
                """, String.class, articleId);
    }

    public record LegacyArticleRow(
            long id,
            String title,
            String summary,
            String content,
            String cover,
            long authorId,
            Integer status,
            Integer isDeleted,
            Long latestRevisionId,
            Long pendingRevisionId,
            Long publishedRevisionId,
            String visibilityState,
            String reviewState,
            long lockVersion) {
    }

    private static Long nullableLong(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Integer nullableInteger(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }
}
