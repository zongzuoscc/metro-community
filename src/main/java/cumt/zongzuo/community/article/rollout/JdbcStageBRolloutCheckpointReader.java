package cumt.zongzuo.community.article.rollout;

import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcStageBRolloutCheckpointReader implements StageBRolloutCheckpointReader {

    private static final String COLUMNS = """
            checkpoint_id,mode,schema_generation,minimum_binary_generation,required_build_digest,
            backfill_started_at,verified_build_digest,verified_fingerprint,verify_report_hash,
            verified_at,sentinel_build_digest,sentinel_report_hash,sentinel_verified_at,
            cutover_epoch,updated_by,updated_at,lock_version
            """;

    private final JdbcTemplate jdbc;

    public JdbcStageBRolloutCheckpointReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StageBRolloutCheckpoint> find() {
        return one("SELECT " + COLUMNS
                + " FROM article_revision_rollout_checkpoint WHERE checkpoint_id=1");
    }

    @Override
    public StageBRolloutCheckpoint require() {
        return find().orElseThrow(() -> new IllegalStateException(
                "article revision rollout checkpoint is missing"));
    }

    @Override
    public StageBRolloutCheckpoint requireForUpdate() {
        return one("SELECT " + COLUMNS
                + " FROM article_revision_rollout_checkpoint WHERE checkpoint_id=1 FOR UPDATE")
                .orElseThrow(() -> new IllegalStateException(
                        "article revision rollout checkpoint is missing"));
    }

    private Optional<StageBRolloutCheckpoint> one(String sql) {
        List<StageBRolloutCheckpoint> rows;
        try {
            rows = jdbc.query(sql, (resultSet, rowNum) -> checkpoint(resultSet));
        }
        catch (RuntimeException invalidCheckpoint) {
            throw new IllegalStateException("article revision rollout checkpoint is unreadable: "
                    + invalidCheckpoint.getMessage(),
                    invalidCheckpoint);
        }
        if (rows.size() > 1) {
            throw new IllegalStateException("article revision rollout checkpoint is not a singleton");
        }
        return rows.stream().findFirst();
    }

    private static StageBRolloutCheckpoint checkpoint(ResultSet resultSet) throws SQLException {
        ArticleRevisionMode mode;
        try {
            mode = ArticleRevisionMode.valueOf(resultSet.getString("mode"));
        }
        catch (RuntimeException invalidMode) {
            throw new IllegalStateException("article revision rollout checkpoint mode is invalid",
                    invalidMode);
        }
        return new StageBRolloutCheckpoint(
                resultSet.getInt("checkpoint_id"), mode,
                resultSet.getLong("schema_generation"),
                resultSet.getLong("minimum_binary_generation"),
                resultSet.getString("required_build_digest"),
                localDateTime(resultSet, "backfill_started_at"),
                resultSet.getString("verified_build_digest"),
                resultSet.getString("verified_fingerprint"),
                resultSet.getString("verify_report_hash"),
                localDateTime(resultSet, "verified_at"),
                resultSet.getString("sentinel_build_digest"),
                resultSet.getString("sentinel_report_hash"),
                localDateTime(resultSet, "sentinel_verified_at"),
                resultSet.getLong("cutover_epoch"),
                resultSet.getString("updated_by"),
                resultSet.getTimestamp("updated_at").toLocalDateTime(),
                resultSet.getLong("lock_version"));
    }

    private static java.time.LocalDateTime localDateTime(ResultSet resultSet, String column)
            throws SQLException {
        java.sql.Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
