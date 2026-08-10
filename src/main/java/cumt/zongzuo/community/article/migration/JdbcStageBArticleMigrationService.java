package cumt.zongzuo.community.article.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.article.model.ArticleContentSnapshot;
import cumt.zongzuo.community.article.persistence.ArticleMigrationMapper;
import cumt.zongzuo.community.article.persistence.ArticleMigrationMapper.LegacyArticleRow;
import cumt.zongzuo.community.article.service.ArticleContentCanonicalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

@Service
public class JdbcStageBArticleMigrationService implements StageBArticleMigrationService {

    static final String ADVISORY_LOCK_NAME = "metro:stage-b:article-revision-backfill:v1";
    private static final int MAX_BATCH_SIZE = 1_000;
    private static final String LEGACY_BACKFILL_MANUAL = "LEGACY_BACKFILL_MANUAL";
    private static final String LEGACY_SHADOW_MANUAL = "LEGACY_SHADOW_MANUAL";

    private final DataSource dataSource;
    private final ArticleContentCanonicalizer canonicalizer;
    private final ObjectMapper objectMapper;
    private final ArticleRevisionModeResolver modeResolver;

    public JdbcStageBArticleMigrationService(DataSource dataSource,
                                             ArticleContentCanonicalizer canonicalizer,
                                             ObjectMapper objectMapper,
                                             ArticleRevisionModeResolver modeResolver) {
        this.dataSource = dataSource;
        this.canonicalizer = canonicalizer;
        this.objectMapper = objectMapper;
        this.modeResolver = modeResolver;
    }

    @Override
    public MigrationBatchResult backfillAfter(long afterArticleId, int limit) {
        assertBackfillMode();
        validateCursor(afterArticleId, limit);
        return withAdvisoryLock(session -> session.transactions().execute(status ->
                backfillBatch(session.jdbc(), afterArticleId, limit)));
    }

    @Override
    public MigrationRunResult backfillAll(int batchSize) {
        assertBackfillMode();
        validateCursor(0, batchSize);
        return withAdvisoryLock(session -> {
            long cursor = 0;
            long scanned = 0;
            long migrated = 0;
            long issues = 0;
            int batches = 0;
            while (true) {
                long after = cursor;
                MigrationBatchResult batch = session.transactions().execute(status ->
                        backfillBatch(session.jdbc(), after, batchSize));
                if (batch == null) {
                    throw new IllegalStateException("backfill transaction returned no result");
                }
                if (batch.scanned() == 0) {
                    break;
                }
                batches++;
                scanned += batch.scanned();
                migrated += batch.migrated();
                issues += batch.issues();
                cursor = batch.lastArticleId();
                if (!batch.hasMore()) {
                    break;
                }
            }
            return new MigrationRunResult(cursor, scanned, migrated, issues, batches);
        });
    }

    private MigrationBatchResult backfillBatch(JdbcTemplate jdbc, long afterArticleId, int limit) {
        List<LegacyArticleRow> rows = ArticleMigrationMapper.selectBatchForUpdate(jdbc, afterArticleId, limit);
        int migrated = 0;
        int issues = 0;
        long lastArticleId = afterArticleId;
        for (LegacyArticleRow row : rows) {
            lastArticleId = row.id();
            ArticleContentSnapshot legacy = canonicalizer.canonicalize(
                    row.title(), row.summary(), row.content(), row.cover(),
                    ArticleMigrationMapper.selectLegacyTags(jdbc, row.id()));
            if (!validStatus(row.status())) {
                recordIssue(jdbc, row.id(), "INVALID_LEGACY_STATUS", legacy.contentHash(),
                        details("status", row.status()));
                issues++;
                continue;
            }
            if (!validDeleteFlag(row.isDeleted())) {
                recordIssue(jdbc, row.id(), "INVALID_DELETE_FLAG", legacy.contentHash(),
                        details("isDeleted", row.isDeleted()));
                issues++;
                continue;
            }

            ExistingShadow existing = loadExistingShadow(jdbc, row.id());
            String mismatch = findMismatch(row, legacy, existing);
            if (mismatch != null) {
                recordIssue(jdbc, row.id(), "BACKFILL_MISMATCH", legacy.contentHash(),
                        details("reason", mismatch));
                issues++;
                continue;
            }

            StoredDraft draft = existing.draft();
            if (draft == null) {
                insertDraft(jdbc, row, legacy);
                draft = loadDraft(jdbc, row.id());
            }
            StoredRevision baseline = existing.revisionOne();
            if (baseline == null) {
                long revisionId = insertBaselineRevision(jdbc, row, legacy, draft.draftVersion());
                baseline = loadRevision(jdbc, revisionId);
            }

            ExistingShadow refreshed = loadExistingShadow(jdbc, row.id());
            StoredRevision target = targetRevision(row, refreshed, baseline);
            initializeArticleState(jdbc, row, target);
            if (row.status() == 2 && refreshed.jobsForRevision(target.id()).isEmpty()) {
                insertManualJob(jdbc, row.id(), target);
            }
            resolveIssues(jdbc, row.id());
            migrated++;
        }
        return new MigrationBatchResult(afterArticleId, lastArticleId, rows.size(), migrated, issues,
                rows.size() == limit);
    }

    private String findMismatch(LegacyArticleRow article, ArticleContentSnapshot legacy,
                                ExistingShadow shadow) {
        StoredDraft draft = shadow.draft();
        if (draft != null && !draftMatches(article, legacy, draft)) {
            return "DRAFT_CONTENT_OR_HASH";
        }
        for (StoredRevision revision : shadow.revisions()) {
            if (revision.articleId() != article.id() || revision.createdBy() != article.authorId()) {
                return "REVISION_OWNERSHIP";
            }
            ArticleContentSnapshot recomputed = canonicalizeStored(revision.title(), revision.summary(),
                    revision.bodyMarkdown(), revision.cover(), revision.tagsJson());
            if (recomputed == null || !Objects.equals(recomputed.contentHash(), revision.contentHash())) {
                return "REVISION_SELF_HASH";
            }
        }

        StoredRevision baseline = shadow.revisionOne();
        StoredRevision target = targetRevision(article, shadow, baseline);
        String expectedVisibility = article.isDeleted() == 1 ? "RECYCLED" : switch (article.status()) {
            case 1 -> "PUBLIC";
            default -> "PRIVATE";
        };
        String expectedReview = switch (article.status()) {
            case 0 -> "NOT_SUBMITTED";
            case 1 -> "APPROVED";
            case 2 -> "AUTO_PENDING";
            case 3 -> "REJECTED";
            default -> throw new IllegalStateException("validated status changed");
        };
        if (article.visibilityState() != null
                && !article.visibilityState().equals(expectedVisibility)) {
            return "VISIBILITY_STATE";
        }
        if (article.reviewState() != null && !article.reviewState().equals(expectedReview)) {
            return "REVIEW_STATE";
        }
        if (!pointersAreCompatible(article, target)) {
            return "ARTICLE_POINTERS";
        }
        if (target != null && article.status() != 0) {
            ArticleContentSnapshot targetSnapshot = canonicalizeStored(target.title(), target.summary(),
                    target.bodyMarkdown(), target.cover(), target.tagsJson());
            if (targetSnapshot == null || !legacy.contentHash().equals(targetSnapshot.contentHash())) {
                return "LEGACY_REVISION_CONTENT";
            }
        }
        if (article.status() == 2 && target != null) {
            List<StoredJob> bound = shadow.jobsForRevision(target.id());
            if (bound.size() > 1) {
                return "MODERATION_JOB_COUNT";
            }
            if (bound.size() == 1) {
                StoredJob job = bound.getFirst();
                if (!Objects.equals(job.contentHash(), target.contentHash())) {
                    return "MODERATION_JOB_HASH";
                }
                if (!"HUMAN_PENDING".equals(job.state())) {
                    return "MODERATION_JOB_STATE";
                }
                if (!isSupportedLegacyPendingReason(job.lastError())) {
                    return "MODERATION_JOB_REASON";
                }
                if (!job.hasUntouchedLegacyFields()) {
                    return "MODERATION_JOB_FROZEN_FIELDS";
                }
            }
        } else if (article.status() == 2 && target == null && !shadow.jobs().isEmpty()) {
            return "MODERATION_JOB_WITHOUT_REVISION";
        }
        return null;
    }

    private boolean isSupportedLegacyPendingReason(String reason) {
        return LEGACY_BACKFILL_MANUAL.equals(reason) || LEGACY_SHADOW_MANUAL.equals(reason);
    }

    private boolean pointersAreCompatible(LegacyArticleRow article, StoredRevision target) {
        Long targetId = target == null ? null : target.id();
        Set<Long> supplied = new HashSet<>();
        if (article.latestRevisionId() != null) {
            supplied.add(article.latestRevisionId());
        }
        if (article.pendingRevisionId() != null) {
            supplied.add(article.pendingRevisionId());
        }
        if (article.publishedRevisionId() != null) {
            supplied.add(article.publishedRevisionId());
        }
        if (supplied.size() > 1 || (!supplied.isEmpty() && !supplied.contains(targetId))) {
            return false;
        }
        return switch (article.status()) {
            case 0 -> supplied.isEmpty();
            case 1 -> article.pendingRevisionId() == null;
            case 2 -> article.publishedRevisionId() == null;
            case 3 -> article.pendingRevisionId() == null && article.publishedRevisionId() == null;
            default -> false;
        };
    }

    private StoredRevision targetRevision(LegacyArticleRow article, ExistingShadow shadow,
                                          StoredRevision baseline) {
        Long targetId = switch (article.status()) {
            case 0 -> baseline == null ? null : baseline.id();
            case 1 -> firstNonNull(article.publishedRevisionId(), article.latestRevisionId(),
                    baseline == null ? null : baseline.id());
            case 2 -> firstNonNull(article.pendingRevisionId(), article.latestRevisionId(),
                    baseline == null ? null : baseline.id());
            case 3 -> firstNonNull(article.latestRevisionId(), baseline == null ? null : baseline.id());
            default -> null;
        };
        return targetId == null ? null : shadow.revisionById(targetId);
    }

    private void initializeArticleState(JdbcTemplate jdbc, LegacyArticleRow article,
                                        StoredRevision target) {
        Long latest = switch (article.status()) {
            case 0 -> null;
            default -> target.id();
        };
        Long pending = article.status() == 2 ? target.id() : null;
        Long published = article.status() == 1 ? target.id() : null;
        String visibility = article.isDeleted() == 1 ? "RECYCLED"
                : article.status() == 1 ? "PUBLIC" : "PRIVATE";
        String review = switch (article.status()) {
            case 0 -> "NOT_SUBMITTED";
            case 1 -> "APPROVED";
            case 2 -> "AUTO_PENDING";
            case 3 -> "REJECTED";
            default -> throw new IllegalStateException("validated status changed");
        };
        boolean changed = !Objects.equals(article.latestRevisionId(), latest)
                || !Objects.equals(article.pendingRevisionId(), pending)
                || !Objects.equals(article.publishedRevisionId(), published)
                || !Objects.equals(article.visibilityState(), visibility)
                || !Objects.equals(article.reviewState(), review);
        if (!changed) {
            return;
        }
        int updated = jdbc.update("""
                UPDATE article
                SET latest_revision_id=?,pending_revision_id=?,published_revision_id=?,
                    visibility_state=?,review_state=?,lock_version=lock_version+1
                WHERE id=? AND lock_version=?
                """, latest, pending, published, visibility, review, article.id(), article.lockVersion());
        if (updated != 1) {
            throw new IllegalStateException("article backfill state lost its row lock");
        }
    }

    private void insertDraft(JdbcTemplate jdbc, LegacyArticleRow article,
                             ArticleContentSnapshot snapshot) {
        jdbc.update("""
                INSERT INTO article_draft(article_id,user_id,draft_version,title,summary,body_markdown,
                    body_plain,cover,tags_json,content_hash,created_at,updated_at,lock_version)
                VALUES(?,?,0,?,?,?,?,?,?,?,NOW(6),NOW(6),0)
                """, article.id(), article.authorId(), snapshot.title(), snapshot.summary(),
                snapshot.bodyMarkdown(), snapshot.bodyPlain(), snapshot.cover(), snapshot.tagsJson(),
                snapshot.contentHash());
    }

    private long insertBaselineRevision(JdbcTemplate jdbc, LegacyArticleRow article,
                                        ArticleContentSnapshot snapshot, long sourceDraftVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator creator = connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO article_revision(article_id,revision_no,title,summary,body_markdown,
                        body_plain,cover,tags_json,content_hash,source_draft_version,created_by,created_at)
                    VALUES(?,1,?,?,?,?,?,?,?,?,?,NOW(6))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, article.id());
            statement.setString(2, snapshot.title());
            statement.setString(3, snapshot.summary());
            statement.setString(4, snapshot.bodyMarkdown());
            statement.setString(5, snapshot.bodyPlain());
            statement.setString(6, snapshot.cover());
            statement.setString(7, snapshot.tagsJson());
            statement.setString(8, snapshot.contentHash());
            statement.setLong(9, sourceDraftVersion);
            statement.setLong(10, article.authorId());
            return statement;
        };
        if (jdbc.update(creator, keyHolder) != 1 || keyHolder.getKey() == null) {
            throw new IllegalStateException("baseline revision insert failed");
        }
        return keyHolder.getKey().longValue();
    }

    private void insertManualJob(JdbcTemplate jdbc, long articleId, StoredRevision revision) {
        jdbc.update("""
                INSERT INTO article_moderation_job(article_id,revision_id,content_hash,state,
                    attempt_count,last_error,created_at,updated_at,lock_version)
                VALUES(?,?,?,'HUMAN_PENDING',0,'LEGACY_BACKFILL_MANUAL',NOW(6),NOW(6),0)
                """, articleId, revision.id(), revision.contentHash());
    }

    private ExistingShadow loadExistingShadow(JdbcTemplate jdbc, long articleId) {
        StoredDraft draft = loadDraft(jdbc, articleId);
        List<StoredRevision> revisions = jdbc.query("""
                SELECT id,article_id,revision_no,title,summary,body_markdown,body_plain,cover,tags_json,
                       content_hash,source_draft_version,created_by,created_at
                FROM article_revision WHERE article_id=? ORDER BY revision_no,id
                """, (rs, rowNum) -> new StoredRevision(
                rs.getLong("id"), rs.getLong("article_id"), rs.getLong("revision_no"),
                rs.getString("title"), rs.getString("summary"), rs.getString("body_markdown"),
                rs.getString("body_plain"), rs.getString("cover"), rs.getString("tags_json"),
                rs.getString("content_hash"), rs.getLong("source_draft_version"),
                rs.getLong("created_by"), rs.getTimestamp("created_at").toLocalDateTime()), articleId);
        List<StoredJob> jobs = jdbc.query("""
                SELECT id,article_id,revision_id,content_hash,state,model_decision,risk_score,
                       policy_hits_json,attempt_count,next_attempt_at,lease_owner,lease_until,
                       last_error,reviewer_id,review_reason,reviewed_at,created_at,updated_at,lock_version
                FROM article_moderation_job WHERE article_id=? ORDER BY id
                """, (rs, rowNum) -> new StoredJob(
                rs.getLong("id"), rs.getLong("article_id"), rs.getLong("revision_id"),
                rs.getString("content_hash"), rs.getString("state"),
                rs.getString("model_decision"), rs.getString("risk_score"),
                rs.getString("policy_hits_json"), rs.getInt("attempt_count"),
                rs.getTimestamp("next_attempt_at"), rs.getString("lease_owner"),
                rs.getTimestamp("lease_until"), rs.getString("last_error"),
                nullableLong(rs, "reviewer_id"), rs.getString("review_reason"),
                rs.getTimestamp("reviewed_at"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(), rs.getLong("lock_version")), articleId);
        return new ExistingShadow(draft, revisions, jobs);
    }

    private StoredDraft loadDraft(JdbcTemplate jdbc, long articleId) {
        List<StoredDraft> rows = jdbc.query("""
                SELECT article_id,user_id,draft_version,title,summary,body_markdown,body_plain,cover,
                       tags_json,content_hash,created_at,updated_at,lock_version
                FROM article_draft WHERE article_id=?
                """, (rs, rowNum) -> new StoredDraft(
                rs.getLong("article_id"), rs.getLong("user_id"), rs.getLong("draft_version"),
                rs.getString("title"), rs.getString("summary"), rs.getString("body_markdown"),
                rs.getString("body_plain"), rs.getString("cover"), rs.getString("tags_json"),
                rs.getString("content_hash"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(), rs.getLong("lock_version")), articleId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredRevision loadRevision(JdbcTemplate jdbc, long revisionId) {
        List<StoredRevision> rows = jdbc.query("""
                SELECT id,article_id,revision_no,title,summary,body_markdown,body_plain,cover,tags_json,
                       content_hash,source_draft_version,created_by,created_at
                FROM article_revision WHERE id=?
                """, (rs, rowNum) -> new StoredRevision(
                rs.getLong("id"), rs.getLong("article_id"), rs.getLong("revision_no"),
                rs.getString("title"), rs.getString("summary"), rs.getString("body_markdown"),
                rs.getString("body_plain"), rs.getString("cover"), rs.getString("tags_json"),
                rs.getString("content_hash"), rs.getLong("source_draft_version"),
                rs.getLong("created_by"), rs.getTimestamp("created_at").toLocalDateTime()), revisionId);
        if (rows.size() != 1) {
            throw new IllegalStateException("inserted revision cannot be reloaded");
        }
        return rows.getFirst();
    }

    private boolean draftMatches(LegacyArticleRow article, ArticleContentSnapshot expected,
                                 StoredDraft draft) {
        if (draft.articleId() != article.id() || draft.userId() != article.authorId()) {
            return false;
        }
        ArticleContentSnapshot recomputed = canonicalizeStored(draft.title(), draft.summary(),
                draft.bodyMarkdown(), draft.cover(), draft.tagsJson());
        return recomputed != null
                && Objects.equals(expected.contentHash(), recomputed.contentHash())
                && Objects.equals(draft.contentHash(), recomputed.contentHash())
                && Objects.equals(draft.bodyPlain(), recomputed.bodyPlain());
    }

    private ArticleContentSnapshot canonicalizeStored(String title, String summary, String body,
                                                       String cover, String tagsJson) {
        try {
            List<String> tags = objectMapper.readerForListOf(String.class).readValue(tagsJson);
            return canonicalizer.canonicalize(title, summary, body, cover, tags);
        } catch (JsonProcessingException | RuntimeException exception) {
            return null;
        }
    }

    private void recordIssue(JdbcTemplate jdbc, long articleId, String code,
                             String observedHash, String detailsJson) {
        jdbc.update("""
                INSERT INTO article_revision_migration_issue(article_id,issue_code,observed_hash,
                    details_json,detected_at,resolved_at,resolution_note)
                VALUES(?,?,?,?,NOW(6),NULL,NULL)
                ON DUPLICATE KEY UPDATE resolved_at=NULL,resolution_note=NULL
                """, articleId, code, observedHash, detailsJson);
    }

    private void resolveIssues(JdbcTemplate jdbc, long articleId) {
        jdbc.update("""
                UPDATE article_revision_migration_issue
                SET resolved_at=NOW(6),resolution_note='AUTO_RESOLVED_AFTER_SUCCESSFUL_BACKFILL'
                WHERE article_id=? AND resolved_at IS NULL
                """, articleId);
    }

    private String details(String name, Object value) {
        ObjectNode details = objectMapper.createObjectNode();
        if (value == null) {
            details.putNull(name);
        } else if (value instanceof Number number) {
            details.put(name, number.longValue());
        } else {
            details.put(name, String.valueOf(value));
        }
        return details.toString();
    }

    private <T> T withAdvisoryLock(Function<LockedSession, T> callback) {
        try (Connection connection = dataSource.getConnection()) {
            SingleConnectionDataSource lockedDataSource = new SingleConnectionDataSource(connection, true);
            JdbcTemplate jdbc = new JdbcTemplate(lockedDataSource);
            Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?,0)", Integer.class,
                    ADVISORY_LOCK_NAME);
            if (!Integer.valueOf(1).equals(acquired)) {
                throw new StageBMigrationLockUnavailableException();
            }
            try {
                DataSourceTransactionManager manager = new DataSourceTransactionManager(lockedDataSource);
                TransactionTemplate transactions = new TransactionTemplate(manager);
                return callback.apply(new LockedSession(jdbc, transactions));
            } finally {
                Integer released = jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class,
                        ADVISORY_LOCK_NAME);
                if (!Integer.valueOf(1).equals(released)) {
                    throw new IllegalStateException("stage B migration advisory lock was not owned at release");
                }
            }
        } catch (StageBMigrationLockUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("stage B article migration failed", exception);
        }
    }

    private static void validateCursor(long afterArticleId, int limit) {
        if (afterArticleId < 0) {
            throw new IllegalArgumentException("afterArticleId must be non-negative");
        }
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("migration batch size must be between 1 and " + MAX_BATCH_SIZE);
        }
    }

    private void assertBackfillMode() {
        ArticleRevisionMode mode = modeResolver.current();
        if (mode != ArticleRevisionMode.SHADOW && mode != ArticleRevisionMode.VERIFY_FENCE) {
            throw new IllegalStateException("Stage B backfill requires SHADOW or VERIFY_FENCE mode");
        }
    }

    private static boolean validStatus(Integer status) {
        return status != null && status >= 0 && status <= 3;
    }

    private static boolean validDeleteFlag(Integer deleted) {
        return deleted != null && (deleted == 0 || deleted == 1);
    }

    private static Long nullableLong(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private record LockedSession(JdbcTemplate jdbc, TransactionTemplate transactions) {
    }

    private record StoredDraft(
            long articleId, long userId, long draftVersion, String title, String summary,
            String bodyMarkdown, String bodyPlain, String cover, String tagsJson, String contentHash,
            LocalDateTime createdAt, LocalDateTime updatedAt, long lockVersion) {
    }

    private record StoredRevision(
            long id, long articleId, long revisionNo, String title, String summary,
            String bodyMarkdown, String bodyPlain, String cover, String tagsJson, String contentHash,
            long sourceDraftVersion, long createdBy, LocalDateTime createdAt) {
    }

    private record StoredJob(
            long id, long articleId, long revisionId, String contentHash, String state,
            String modelDecision, String riskScore, String policyHitsJson, int attemptCount,
            java.sql.Timestamp nextAttemptAt, String leaseOwner, java.sql.Timestamp leaseUntil,
            String lastError, Long reviewerId, String reviewReason, java.sql.Timestamp reviewedAt,
            LocalDateTime createdAt, LocalDateTime updatedAt, long lockVersion) {

        boolean hasUntouchedLegacyFields() {
            return modelDecision == null && riskScore == null && policyHitsJson == null
                    && attemptCount == 0 && nextAttemptAt == null && leaseOwner == null
                    && leaseUntil == null && reviewerId == null && reviewReason == null
                    && reviewedAt == null && lockVersion == 0;
        }
    }

    private record ExistingShadow(StoredDraft draft, List<StoredRevision> revisions,
                                  List<StoredJob> jobs) {
        StoredRevision revisionOne() {
            return revisions.stream().filter(revision -> revision.revisionNo() == 1).findFirst().orElse(null);
        }

        StoredRevision revisionById(long id) {
            return revisions.stream().filter(revision -> revision.id() == id).findFirst().orElse(null);
        }

        List<StoredJob> jobsForRevision(long revisionId) {
            return jobs.stream().filter(job -> job.revisionId() == revisionId).toList();
        }
    }
}
