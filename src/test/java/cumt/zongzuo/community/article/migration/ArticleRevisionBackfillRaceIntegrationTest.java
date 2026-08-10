package cumt.zongzuo.community.article.migration;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.article.service.ArticleMutationFacade;
import cumt.zongzuo.community.article.web.SaveArticleDraftCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = "metro.article.revision-mode=SHADOW")
class ArticleRevisionBackfillRaceIntegrationTest extends IntegrationTestSupport {

    private static final long AUTHOR_ID = 94_200L;
    private static final String FAILURE_TRIGGER = "test_stage_b_backfill_failure";

    @Autowired
    private StageBArticleMigrationService migrationService;

    @Autowired
    private ArticleMutationFacade mutationFacade;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void resetFixture() {
        dropFailureTrigger();
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL WHERE id>=94200");
        jdbcTemplate.update("DELETE FROM article_moderation_job WHERE article_id>=94200");
        jdbcTemplate.update("DELETE FROM article_revision_migration_issue WHERE article_id>=94200");
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id>=94200");
        jdbcTemplate.update("DELETE FROM article_draft WHERE article_id>=94200");
        jdbcTemplate.update("DELETE FROM article_tag WHERE article_id>=94200");
        jdbcTemplate.update("DELETE FROM article WHERE id>=94200");
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status)
                VALUES(?, 'migration-race-author', 'unused', 'migration-race@example.com', 0, 0)
                ON DUPLICATE KEY UPDATE status=0
                """, AUTHOR_ID);
    }

    @AfterEach
    void removeFailureTrigger() {
        dropFailureTrigger();
    }

    @Test
    void competingOperatorCannotEnterAndTheNamedLockIsReleasedAfterSuccess() throws Exception {
        seedDraftArticle(94_201L, "lock-body");
        try (Connection owner = dataSource.getConnection()) {
            assertThat(queryLock(owner, "SELECT GET_LOCK('"
                    + JdbcStageBArticleMigrationService.ADVISORY_LOCK_NAME + "',0)"))
                    .isEqualTo(1);
            assertThatThrownBy(() -> migrationService.backfillAfter(94_199L, 10))
                    .isInstanceOf(StageBMigrationLockUnavailableException.class);
            assertThat(queryLock(owner, "SELECT RELEASE_LOCK('"
                    + JdbcStageBArticleMigrationService.ADVISORY_LOCK_NAME + "')"))
                    .isEqualTo(1);
        }

        migrationService.backfillAfter(94_199L, 10);

        assertThat(jdbcTemplate.queryForObject("SELECT IS_FREE_LOCK(?)", Integer.class,
                JdbcStageBArticleMigrationService.ADVISORY_LOCK_NAME)).isEqualTo(1);
    }

    @Test
    void aMidBatchFailureRollsBackEveryRowReleasesTheLockAndCanRestartFromZero() {
        seedDraftArticle(94_211L, "first");
        seedDraftArticle(94_212L, "second");
        rootJdbc().execute("""
                CREATE TRIGGER test_stage_b_backfill_failure
                BEFORE INSERT ON article_revision FOR EACH ROW
                BEGIN
                  IF NEW.article_id=94212 THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TEST_BACKFILL_FAILURE';
                  END IF;
                END
                """);

        assertThatThrownBy(() -> migrationService.backfillAfter(94_209L, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("TEST_BACKFILL_FAILURE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM article_revision WHERE article_id IN (94211,94212)
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM article_draft WHERE article_id IN (94211,94212)
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT IS_FREE_LOCK(?)", Integer.class,
                JdbcStageBArticleMigrationService.ADVISORY_LOCK_NAME)).isEqualTo(1);

        dropFailureTrigger();
        MigrationRunResult recovered = migrationService.backfillAll(1);

        assertThat(recovered.scanned()).isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM article_revision WHERE article_id IN (94211,94212)
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void shadowMutationAfterBackfillAdvancesOnlyCurrentDraftAndKeepsBaselineFrozen() {
        seedDraftArticle(94_221L, "before-backfill");
        migrationService.backfillAfter(94_219L, 10);
        String baselineHash = jdbcTemplate.queryForObject("""
                SELECT content_hash FROM article_revision WHERE article_id=94221 AND revision_no=1
                """, String.class);

        mutationFacade.saveDraft(new SaveArticleDraftCommand(
                94_221L, 0, "article-94221", "summary", "after-backfill", "cover", List.of()), AUTHOR_ID);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM article WHERE id=94221", String.class)).isEqualTo("after-backfill");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_markdown FROM article_draft WHERE article_id=94221", String.class))
                .isEqualTo("after-backfill");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT content_hash FROM article_revision WHERE article_id=94221 AND revision_no=1
                """, String.class)).isEqualTo(baselineHash);
    }

    @Test
    void shadowMutationBeforeBackfillAlsoKeepsItsPreMutationBaselineAndCurrentDraft() {
        seedDraftArticle(94_231L, "before-mutation");

        mutationFacade.saveDraft(new SaveArticleDraftCommand(
                94_231L, 0, "article-94231", "summary", "after-mutation", "cover", List.of()), AUTHOR_ID);
        String baselineBody = jdbcTemplate.queryForObject("""
                SELECT body_markdown FROM article_revision WHERE article_id=94231 AND revision_no=1
                """, String.class);
        migrationService.backfillAfter(94_229L, 10);

        assertThat(baselineBody).isEqualTo("before-mutation");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_markdown FROM article_draft WHERE article_id=94231", String.class))
                .isEqualTo("after-mutation");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM article_revision_migration_issue
                WHERE article_id=94231 AND resolved_at IS NULL
                """, Integer.class)).isZero();
    }

    @Test
    void simultaneousShadowMutationAndBackfillSerializeOnTheArticleRow() throws Exception {
        seedDraftArticle(94_241L, "before-race");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> backfill = executor.submit(() -> {
                await(start);
                migrationService.backfillAfter(94_239L, 10);
            });
            Future<?> mutation = executor.submit(() -> {
                await(start);
                mutationFacade.saveDraft(new SaveArticleDraftCommand(
                        94_241L, 0, "article-94241", "summary", "after-race", "cover", List.of()),
                        AUTHOR_ID);
            });
            start.countDown();
            backfill.get(15, TimeUnit.SECONDS);
            mutation.get(15, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM article WHERE id=94241", String.class)).isEqualTo("after-race");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_markdown FROM article_draft WHERE article_id=94241", String.class))
                .isEqualTo("after-race");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM article_revision WHERE article_id=94241 AND revision_no=1
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM article_revision_migration_issue
                WHERE article_id=94241 AND resolved_at IS NULL
                """, Integer.class)).isZero();
    }

    private void seedDraftArticle(long id, String body) {
        jdbcTemplate.update("""
                INSERT INTO article(id,title,content,summary,cover,author_id,view_count,like_count,
                    comment_count,collect_count,create_time,update_time,status,is_deleted,
                    lifecycle_epoch,lock_version)
                VALUES(?, ?, ?, 'summary', 'cover', ?, 0, 0, 0, 0, NOW(6), NOW(6), 0, 0, 1, 0)
                """, id, "article-" + id, body, AUTHOR_ID);
    }

    private int queryLock(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private void dropFailureTrigger() {
        rootJdbc().execute("DROP TRIGGER IF EXISTS " + FAILURE_TRIGGER);
    }

    private JdbcTemplate rootJdbc() {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            return new JdbcTemplate(new DriverManagerDataSource(url, "root", "test"));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot create root test datasource", exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
