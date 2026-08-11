package cumt.zongzuo.community.article.migration;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.service.ArticleContentCanonicalizer;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = false)
@Execution(ExecutionMode.SAME_THREAD)
class ArticleRevisionSchemaIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/database/migrations/2026-08-10-article-revision-moderation-outbox.sql");
    private static final Path GRANTS = Path.of(
            "docs/database/operations/2026-08-10-stage-b-immutable-table-grants.sql");
    private static final Path ROLLOUT_GRANTS = Path.of(
            "docs/database/operations/2026-08-10-stage-b-rollout-checkpoint-grants.sql");
    private static final Path EXPAND_RUNBOOK = Path.of(
            "docs/database/operations/2026-08-10-stage-b-schema-expand-runbook.md");
    private static final String THIRD_ARTICLE_COLUMN_MARKER = "TEST_CHECKPOINT_AFTER_THIRD_ARTICLE_COLUMN";
    private static final String BEFORE_POINTERS_MARKER = "TEST_CHECKPOINT_BEFORE_ARTICLE_POINTERS";
    private static final String AFTER_ROLLOUT_CHECKPOINT_MARKER =
            "TEST_CHECKPOINT_AFTER_ROLLOUT_CHECKPOINT_CREATE";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void migrationIsIdempotentAndMatchesTheCompleteSchemaManifest() {
        TestDatabase database = newLegacyDatabase();

        runMigration(database.dataSource());
        assertCompleteManifest(database.jdbc());
        String firstFingerprint = metadataFingerprint(database.jdbc());
        Map<String, Long> firstCounts = tableCounts(database.jdbc());

        runMigration(database.dataSource());

        assertCompleteManifest(database.jdbc());
        assertThat(metadataFingerprint(database.jdbc())).isEqualTo(firstFingerprint);
        assertThat(tableCounts(database.jdbc())).isEqualTo(firstCounts);
        assertCrossArticlePointerIsRejected(database.jdbc());
    }

    @Test
    void migrationWidensTheLegacyMirrorAndMakesTagIdentityExactAndNoPad() {
        TestDatabase database = newLegacyDatabase();
        database.jdbc().update("INSERT INTO article(id,title,content,author_id) VALUES (7,'legacy','body',70)");
        database.jdbc().update("INSERT INTO tag(id,name) VALUES (8,'AI')");

        runMigration(database.dataSource());

        assertThat(column(database.jdbc(), "article", "content"))
                .isEqualTo(nullable("content", "mediumtext"));
        assertThat(column(database.jdbc(), "tag", "name"))
                .isEqualTo(exactRequired("name", "varchar(50)"));
        assertThat(columnComment(database.jdbc(), "article", "content")).isEqualTo("内容");
        assertThat(columnComment(database.jdbc(), "tag", "name")).isEqualTo("标签名");
        assertThat(database.jdbc().queryForObject("""
                SELECT pad_attribute
                FROM information_schema.collations
                WHERE collation_name='utf8mb4_0900_bin'
                """, String.class)).isEqualTo("NO PAD");
        assertThat(index(database.jdbc(), "tag", "uk_name"))
                .isEqualTo(new IndexContract(false, List.of("name"),
                        java.util.Collections.singletonList(null)));
        assertThat(database.jdbc().queryForObject("SELECT content FROM article WHERE id=7", String.class))
                .isEqualTo("body");
        assertThat(database.jdbc().queryForObject("SELECT name FROM tag WHERE id=8", String.class))
                .isEqualTo("AI");

        runMigration(database.dataSource());

        assertThat(column(database.jdbc(), "article", "content"))
                .isEqualTo(nullable("content", "mediumtext"));
        assertThat(column(database.jdbc(), "tag", "name"))
                .isEqualTo(exactRequired("name", "varchar(50)"));
    }

    @Test
    void migrationUpgradesAHistoricalTwentyColumnOutboxBeforeExactValidation() {
        TestDatabase database = newLegacyDatabase();
        createHistoricalOutbox20(database.jdbc());
        database.jdbc().update("""
                INSERT INTO domain_event_outbox
                    (id,event_id,aggregate_type,aggregate_id,aggregate_version,lifecycle_epoch,
                     event_type,payload_version,payload_json,dedupe_key,occurred_at,state,
                     retry_count,next_attempt_at,created_at)
                VALUES (901,UNHEX(REPEAT('1',32)),'ARTICLE',7,1,1,'TEST',1,
                        JSON_OBJECT(),'legacy-retention-row',NOW(6),'PENDING',0,NOW(6),NOW(6))
                """);

        runMigration(database.dataSource());

        assertCompleteManifest(database.jdbc());
        assertThat(database.jdbc().queryForMap("""
                SELECT dedupe_key,dead_resolved_at,dead_resolved_by,dead_resolution
                FROM domain_event_outbox WHERE id=901
                """))
                .containsEntry("dedupe_key", "legacy-retention-row")
                .containsEntry("dead_resolved_at", null)
                .containsEntry("dead_resolved_by", null)
                .containsEntry("dead_resolution", null);
    }

    @Test
    void migrationResumesAfterTheThirdArticleColumnAndReachesTheFreshTarget() {
        TestDatabase interrupted = newLegacyDatabase();

        runMigrationPrefix(interrupted.dataSource(), THIRD_ARTICLE_COLUMN_MARKER);
        assertThat(column(interrupted.jdbc(), "article", "published_revision_id")).isNotNull();
        assertThat(column(interrupted.jdbc(), "article", "visibility_state")).isNull();

        runMigration(interrupted.dataSource());
        assertCompleteManifest(interrupted.jdbc());
        String interruptedFingerprint = metadataFingerprint(interrupted.jdbc());

        TestDatabase fresh = newLegacyDatabase();
        runMigration(fresh.dataSource());

        assertCompleteManifest(fresh.jdbc());
        assertThat(interruptedFingerprint).isEqualTo(metadataFingerprint(fresh.jdbc()));
    }

    @Test
    void migrationResumesAfterRolloutCheckpointCreationAndValidatesTheSingleton() {
        TestDatabase interrupted = newLegacyDatabase();

        runMigrationPrefix(interrupted.dataSource(), AFTER_ROLLOUT_CHECKPOINT_MARKER);
        assertThat(interrupted.jdbc().queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE()
                  AND table_name='article_revision_rollout_checkpoint'
                """, Integer.class)).isOne();

        runMigration(interrupted.dataSource());
        assertCompleteManifest(interrupted.jdbc());
        assertThatThrownBy(() -> interrupted.jdbc().update("""
                INSERT INTO article_revision_rollout_checkpoint
                    (checkpoint_id,mode,schema_generation,minimum_binary_generation,
                     required_build_digest,cutover_epoch,updated_by,updated_at,lock_version)
                VALUES (2,'LEGACY',1,1,REPEAT('a',64),0,'test',NOW(6),0)
                """))
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void migrationRejectsAnyAdditionalRolloutCheckpointCheckConstraint() {
        TestDatabase database = newLegacyDatabase();
        runMigrationPrefix(database.dataSource(), AFTER_ROLLOUT_CHECKPOINT_MARKER);
        database.jdbc().execute("""
                ALTER TABLE article_revision_rollout_checkpoint
                ADD CONSTRAINT chk_article_revision_rollout_mode_legacy CHECK (mode='LEGACY')
                """);

        assertThatThrownBy(() -> runMigration(database.dataSource()))
                .hasStackTraceContaining("SCHEMA_DRIFT")
                .hasStackTraceContaining("article_revision_rollout_checkpoint_singleton");
    }

    @Test
    void migrationFailsQuicklyOnArticleMetadataLockAndRecoversFromThePartialPrefix() throws Exception {
        TestDatabase database = newLegacyDatabase();
        runMigrationPrefix(database.dataSource(), THIRD_ARTICLE_COLUMN_MARKER);
        assertThat(column(database.jdbc(), "article", "published_revision_id")).isNotNull();
        assertThat(column(database.jdbc(), "article", "visibility_state")).isNull();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> migration = null;
        try (Connection blocker = database.dataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement();
                 var ignored = statement.executeQuery("SELECT * FROM article")) {
                assertThat(ignored.next()).isFalse();
            }

            long startedAt = System.nanoTime();
            migration = executor.submit(() -> runMigration(database.dataSource()));
            Future<?> submittedMigration = migration;
            assertThatThrownBy(() -> submittedMigration.get(4, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasStackTraceContaining("Lock wait timeout exceeded");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
                    .isLessThan(3_900L);

            blocker.rollback();
        } finally {
            if (migration != null && !migration.isDone()) {
                migration.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        runMigration(database.dataSource());
        assertCompleteManifest(database.jdbc());
    }

    @Test
    void freshInstallScriptHasTheSameStageBSchemaAsTheForwardMigration() {
        TestDatabase migrated = newLegacyDatabase();
        runMigration(migrated.dataSource());
        String migrationTarget = stageBMetadataFingerprint(migrated.jdbc());

        TestDatabase fresh = newEmptyDatabase();
        new ResourceDatabasePopulator(new FileSystemResource("script.sql")).execute(fresh.dataSource());

        assertCompleteManifest(fresh.jdbc());
        assertThat(stageBMetadataFingerprint(fresh.jdbc())).isEqualTo(migrationTarget);
    }

    @Test
    void preExistingCorrectIndexAndPointerForeignKeyAreIdempotent() {
        TestDatabase database = newLegacyDatabase();
        runMigrationPrefix(database.dataSource(), BEFORE_POINTERS_MARKER);
        database.jdbc().execute("""
                CREATE INDEX idx_article_published_pointer
                    ON article(published_revision_id, id)
                """);
        database.jdbc().execute("""
                ALTER TABLE article ADD CONSTRAINT fk_article_published_revision
                    FOREIGN KEY(published_revision_id, id)
                    REFERENCES article_revision(id, article_id) ON DELETE RESTRICT
                """);
        assertThat(foreignKey(database.jdbc(), "article", "fk_article_published_revision"))
                .isEqualTo(new ForeignKeyContract("article", "fk_article_published_revision",
                        "article_revision", "NO ACTION", "RESTRICT", List.of(
                        new ForeignKeyColumn("published_revision_id", "article_revision", "id"),
                        new ForeignKeyColumn("id", "article_revision", "article_id"))));

        runMigration(database.dataSource());

        assertCompleteManifest(database.jdbc());
        assertThat(index(database.jdbc(), "article", "idx_article_published_pointer").columns())
                .containsExactly("published_revision_id", "id");
        assertThat(foreignKey(database.jdbc(), "article", "fk_article_published_revision").columns())
                .containsExactly(
                        new ForeignKeyColumn("published_revision_id", "article_revision", "id"),
                        new ForeignKeyColumn("id", "article_revision", "article_id"));
    }

    @Test
    void incompatibleSameNameColumnFailsClosedAsSchemaDrift() {
        TestDatabase database = newLegacyDatabase();
        database.jdbc().execute("ALTER TABLE article ADD COLUMN latest_revision_id VARCHAR(12) NULL");

        assertThatThrownBy(() -> runMigration(database.dataSource()))
                .hasStackTraceContaining("SCHEMA_DRIFT")
                .hasStackTraceContaining("article_latest_revision_id");
    }

    @Test
    void invisibleSameNameColumnFailsClosedAsSchemaDrift() {
        TestDatabase database = newLegacyDatabase();
        database.jdbc().execute("ALTER TABLE article ADD COLUMN latest_revision_id BIGINT NULL INVISIBLE");

        assertThatThrownBy(() -> runMigration(database.dataSource()))
                .hasStackTraceContaining("SCHEMA_DRIFT")
                .hasStackTraceContaining("article_latest_revision_id");
    }

    @Test
    void incompatibleSameNameIndexFailsClosedAsSchemaDrift() {
        TestDatabase database = newLegacyDatabase();
        runMigrationPrefix(database.dataSource(), BEFORE_POINTERS_MARKER);
        database.jdbc().execute("""
                CREATE INDEX idx_article_published_pointer
                    ON article(id, published_revision_id)
                """);

        assertThatThrownBy(() -> runMigration(database.dataSource()))
                .hasStackTraceContaining("SCHEMA_DRIFT")
                .hasStackTraceContaining("idx_article_published_pointer");
    }

    @Test
    void incompatibleSameNameForeignKeyFailsClosedAsSchemaDrift() {
        TestDatabase database = newLegacyDatabase();
        runMigrationPrefix(database.dataSource(), BEFORE_POINTERS_MARKER);
        database.jdbc().execute("""
                ALTER TABLE article ADD CONSTRAINT fk_article_published_revision
                    FOREIGN KEY(latest_revision_id, id)
                    REFERENCES article_revision(id, article_id) ON DELETE RESTRICT
                """);

        assertThatThrownBy(() -> runMigration(database.dataSource()))
                .hasStackTraceContaining("SCHEMA_DRIFT")
                .hasStackTraceContaining("fk_article_published_revision");
    }

    @Test
    void immutableRowsExposeTypedNarrowMappersAndArticlePointers() throws Exception {
        assertTable("cumt.zongzuo.community.article.model.ArticleDraft", "article_draft");
        assertTable("cumt.zongzuo.community.article.model.ArticleRevision", "article_revision");
        assertTable("cumt.zongzuo.community.ai.moderation.revision.ArticleModerationJob",
                "article_moderation_job");
        assertTable("cumt.zongzuo.community.ai.moderation.revision.ArticleModerationAttempt",
                "article_moderation_attempt");

        assertMapper("cumt.zongzuo.community.article.persistence.ArticleDraftMapper", true);
        assertMapper("cumt.zongzuo.community.article.persistence.ArticleRevisionMapper", false);
        assertMapper("cumt.zongzuo.community.ai.moderation.revision.ArticleModerationJobMapper", true);
        assertMapper("cumt.zongzuo.community.ai.moderation.revision.ArticleModerationAttemptMapper", false);

        Class<?> article = Class.forName("cumt.zongzuo.community.entity.Article");
        List<String> internalFields = List.of("latestRevisionId", "pendingRevisionId", "publishedRevisionId",
                "visibilityState", "reviewState", "lifecycleEpoch", "lockVersion");
        for (String fieldName : internalFields) {
            var field = article.getDeclaredField(fieldName);
            assertThat(field.getAnnotation(JsonIgnore.class)).as(fieldName + " JSON visibility").isNotNull();
            assertThat(field.getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class))
                    .as(fieldName + " update strategy")
                    .extracting(com.baomidou.mybatisplus.annotation.TableField::updateStrategy)
                    .isEqualTo(FieldStrategy.NEVER);
        }
        String json = new ObjectMapper().writeValueAsString(article.getConstructor().newInstance());
        assertThat(json).doesNotContain("latestRevisionId", "pendingRevisionId", "publishedRevisionId",
                "visibilityState", "reviewState", "lifecycleEpoch", "lockVersion");
    }

    @Test
    void immutableGrantTemplateRejectsAccountsWithSchemaGlobalOrColumnMutationPrivilege() throws IOException {
        TestDatabase database = newLegacyDatabase();
        runMigration(database.dataSource());
        DataSource rootDataSource = rootDataSource();
        JdbcTemplate root = new JdbcTemplate(rootDataSource);
        List<String> unsafeGrants = List.of(
                "GRANT UPDATE ON `%s`.* TO '%s'@'%%'",
                "GRANT ALL PRIVILEGES ON `%s`.* TO '%s'@'%%'",
                "GRANT DELETE ON *.* TO '%s'@'%%'",
                "GRANT UPDATE (title) ON `%s`.`article_revision` TO '%s'@'%%'");
        for (int index = 0; index < unsafeGrants.size(); index++) {
            String user = "stage_b_unsafe_" + index;
            String role = "stage_b_unsafe_role_" + index;
            String password = "test-" + UUID.randomUUID();
            dropAccount(root, user, role);
            try {
                root.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + password + "'");
                String grant = unsafeGrants.get(index);
                root.execute(grant.contains("`%s`")
                        ? grant.formatted(MYSQL.getDatabaseName(), user)
                        : grant.formatted(user));

                assertThatThrownBy(() -> executeScript(rootDataSource,
                        renderGrantTemplate(MYSQL.getDatabaseName(), user, "%", role, "%")))
                        .hasStackTraceContaining("IMMUTABLE_GRANT_DRIFT_EFFECTIVE_MUTATION_PRIVILEGE");
            } finally {
                dropAccount(root, user, role);
            }
        }
    }

    @Test
    void immutableRoleAllowsSelectAndInsertButDeniesRevisionAndAttemptMutation() throws IOException {
        TestDatabase database = newLegacyDatabase();
        runMigration(database.dataSource());
        DataSource rootDataSource = rootDataSource();
        JdbcTemplate root = new JdbcTemplate(rootDataSource);
        String user = "stage_b_writer";
        String role = "stage_b_immutable";
        String password = "test-" + UUID.randomUUID();
        dropAccount(root, user, role);
        try {
            root.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + password + "'");
            executeScript(rootDataSource,
                    renderGrantTemplate(MYSQL.getDatabaseName(), user, "%", role, "%"));

            DataSource appDataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), user, password);
            JdbcTemplate app = new JdbcTemplate(appDataSource);
            assertThat(app.queryForObject("SELECT CURRENT_ROLE()", String.class))
                    .contains("`" + role + "`@`%`");

            root.update("INSERT INTO article(id,title,author_id) VALUES (201,'immutable',2001)");
            assertThat(app.update("""
                    INSERT INTO article_revision(article_id,revision_no,title,tags_json,content_hash,
                        source_draft_version,created_by,created_at)
                    VALUES (201,1,'immutable',JSON_ARRAY(),REPEAT('a',64),1,2001,NOW(6))
                    """)).isOne();
            Long revisionId = app.queryForObject(
                    "SELECT id FROM article_revision WHERE article_id=201", Long.class);
            root.update("""
                    INSERT INTO article_moderation_job(article_id,revision_id,content_hash,state,
                        next_attempt_at,created_at,updated_at)
                    VALUES (201,?,REPEAT('a',64),'PENDING',NOW(6),NOW(6),NOW(6))
                    """, revisionId);
            Long jobId = root.queryForObject(
                    "SELECT id FROM article_moderation_job WHERE revision_id=?", Long.class, revisionId);
            assertThat(app.update("""
                    INSERT INTO article_moderation_attempt(job_id,attempt_no,prompt_version,input_hash,
                        latency_ms,created_at)
                    VALUES (?,1,'v1',REPEAT('b',64),10,NOW(6))
                    """, jobId)).isOne();
            assertThat(app.queryForObject("SELECT COUNT(*) FROM article_revision", Long.class)).isOne();
            assertThat(app.queryForObject("SELECT COUNT(*) FROM article_moderation_attempt", Long.class)).isOne();

            assertMutationDenied(() -> app.update(
                    "UPDATE article_revision SET title='changed' WHERE id=?", revisionId));
            assertMutationDenied(() -> app.update(
                    "DELETE FROM article_revision WHERE id=?", revisionId));
            assertMutationDenied(() -> app.update(
                    "UPDATE article_moderation_attempt SET latency_ms=11 WHERE job_id=?", jobId));
            assertMutationDenied(() -> app.update(
                    "DELETE FROM article_moderation_attempt WHERE job_id=?", jobId));
        } finally {
            dropAccount(root, user, role);
        }
    }

    @Test
    void rolloutCheckpointRolesSeparateRuntimeReadFromOperatorMutation() throws IOException {
        TestDatabase database = newLegacyDatabase();
        runMigration(database.dataSource());
        DataSource rootDataSource = rootDataSource();
        JdbcTemplate root = new JdbcTemplate(rootDataSource);
        String runtimeUser = "stage_b_rollout_runtime";
        String runtimeRole = "stage_b_rollout_reader";
        String operatorUser = "stage_b_rollout_operator";
        String operatorRole = "stage_b_rollout_writer";
        String runtimePassword = "test-" + UUID.randomUUID();
        String operatorPassword = "test-" + UUID.randomUUID();
        dropAccount(root, runtimeUser, runtimeRole);
        dropAccount(root, operatorUser, operatorRole);
        try {
            root.execute("CREATE USER '" + runtimeUser + "'@'%' IDENTIFIED BY '"
                    + runtimePassword + "'");
            root.execute("CREATE USER '" + operatorUser + "'@'%' IDENTIFIED BY '"
                    + operatorPassword + "'");
            root.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON `" + MYSQL.getDatabaseName()
                    + "`.`article` TO '" + runtimeUser + "'@'%'");
            executeScript(rootDataSource, renderRolloutGrantTemplate(
                    MYSQL.getDatabaseName(), runtimeUser, "%", runtimeRole, "%",
                    operatorUser, "%", operatorRole, "%"));

            JdbcTemplate runtime = new JdbcTemplate(new DriverManagerDataSource(
                    MYSQL.getJdbcUrl(), runtimeUser, runtimePassword));
            JdbcTemplate operator = new JdbcTemplate(new DriverManagerDataSource(
                    MYSQL.getJdbcUrl(), operatorUser, operatorPassword));
            assertThat(runtime.queryForObject(
                    "SELECT COUNT(*) FROM article_revision_rollout_checkpoint", Long.class))
                    .isZero();
            assertMutationDenied(() -> runtime.update("""
                    INSERT INTO article_revision_rollout_checkpoint
                        (checkpoint_id,mode,schema_generation,minimum_binary_generation,
                         required_build_digest,cutover_epoch,updated_by,updated_at,lock_version)
                    VALUES (1,'LEGACY',1,1,REPEAT('a',64),0,'runtime',NOW(6),0)
                    """));

            assertThat(operator.update("""
                    INSERT INTO article_revision_rollout_checkpoint
                        (checkpoint_id,mode,schema_generation,minimum_binary_generation,
                         required_build_digest,cutover_epoch,updated_by,updated_at,lock_version)
                    VALUES (1,'LEGACY',1,1,REPEAT('a',64),0,'operator',NOW(6),0)
                    """)).isOne();
            assertThat(operator.update("""
                    UPDATE article_revision_rollout_checkpoint
                    SET mode='SHADOW',lock_version=lock_version+1
                    WHERE checkpoint_id=1 AND mode='LEGACY' AND lock_version=0
                    """)).isOne();
            assertThat(runtime.queryForObject(
                    "SELECT mode FROM article_revision_rollout_checkpoint WHERE checkpoint_id=1",
                    String.class)).isEqualTo("SHADOW");
            assertMutationDenied(() -> runtime.update("""
                    UPDATE article_revision_rollout_checkpoint SET mode='CUTOVER'
                    WHERE checkpoint_id=1
                    """));
            assertMutationDenied(() -> operator.update(
                    "DELETE FROM article_revision_rollout_checkpoint WHERE checkpoint_id=1"));
            assertMutationDenied(() -> runtime.execute(
                    "ALTER TABLE article_revision_rollout_checkpoint ADD COLUMN runtime_escape INT NULL"));
            assertMutationDenied(() -> operator.execute(
                    "ALTER TABLE article_revision_rollout_checkpoint ADD COLUMN operator_escape INT NULL"));
            assertMutationDenied(() -> runtime.execute(
                    "TRUNCATE TABLE article_revision_rollout_checkpoint"));
            assertMutationDenied(() -> operator.execute(
                    "TRUNCATE TABLE article_revision_rollout_checkpoint"));
            assertThat(runtime.update(
                    "INSERT INTO article(id,title,author_id) VALUES (9791,'runtime-app-right',9790)"))
                    .isOne();
            assertThat(runtime.update(
                    "UPDATE article SET title='runtime-app-right-updated' WHERE id=9791"))
                    .isOne();
            assertThat(runtime.update("DELETE FROM article WHERE id=9791")).isOne();

            root.update("""
                    INSERT INTO article(id,title,summary,content,author_id,status,is_deleted)
                    VALUES (9792,'operator-backfill','summary','body',9790,1,0)
                    """);
            ObjectMapper objectMapper = new ObjectMapper();
            JdbcStageBArticleMigrationService migrationService = new JdbcStageBArticleMigrationService(
                    operator.getDataSource(), new ArticleContentCanonicalizer(objectMapper), objectMapper,
                    () -> ArticleRevisionMode.SHADOW);
            assertThat(migrationService.backfillAll(10))
                    .extracting(MigrationRunResult::migrated, MigrationRunResult::issues)
                    .containsExactly(1L, 0L);
            Long migratedRevisionId = root.queryForObject("""
                    SELECT id FROM article_revision WHERE article_id=9792 AND revision_no=1
                    """, Long.class);
            root.update("""
                    INSERT INTO article_moderation_job(
                        article_id,revision_id,content_hash,state,attempt_count,
                        created_at,updated_at,lock_version)
                    SELECT article_id,id,content_hash,'HUMAN_PENDING',1,NOW(6),NOW(6),1
                    FROM article_revision WHERE id=?
                    """, migratedRevisionId);
            Long migratedJobId = root.queryForObject(
                    "SELECT id FROM article_moderation_job WHERE revision_id=?",
                    Long.class, migratedRevisionId);
            root.update("""
                    INSERT INTO article_moderation_attempt(
                        job_id,attempt_no,provider,model,prompt_version,input_hash,
                        structured_output_json,latency_ms,error_code,created_at)
                    VALUES (?,1,'deepseek','schema-test','v1',REPEAT('b',64),
                            JSON_OBJECT('chunk',JSON_OBJECT('index',0)),1,'PROVIDER_TIMEOUT',NOW(6))
                    """, migratedJobId);
            assertThat(operator.queryForObject(
                    "SELECT COUNT(*) FROM article_moderation_attempt", Long.class)).isOne();
            assertMutationDenied(() -> operator.update("""
                    INSERT INTO article_moderation_attempt(
                        job_id,attempt_no,prompt_version,input_hash,latency_ms,created_at)
                    VALUES (?,2,'v1',REPEAT('c',64),1,NOW(6))
                    """, migratedJobId));
            assertMutationDenied(() -> operator.update(
                    "UPDATE article_moderation_attempt SET latency_ms=2 WHERE job_id=?",
                    migratedJobId));
            assertMutationDenied(() -> operator.update(
                    "DELETE FROM article_moderation_attempt WHERE job_id=?", migratedJobId));
            StageBMigrationProperties migrationProperties = new StageBMigrationProperties();
            migrationProperties.setVerificationPageSize(10);
            assertThat(new JdbcStageBArticleFingerprintService(operator, migrationProperties).fingerprint())
                    .matches("[0-9a-f]{64}");
        } finally {
            dropAccount(root, runtimeUser, runtimeRole);
            dropAccount(root, operatorUser, operatorRole);
        }
    }

    @Test
    void rolloutCheckpointGrantTemplateRejectsDdlAuthorityAndAliasedIdentities() throws IOException {
        TestDatabase database = newLegacyDatabase();
        runMigration(database.dataSource());
        DataSource rootDataSource = rootDataSource();
        JdbcTemplate root = new JdbcTemplate(rootDataSource);

        List<String> unsafeGrants = List.of(
                "GRANT ALTER ON `%s`.* TO '%s'@'%%'",
                "GRANT DROP ON `%s`.* TO '%s'@'%%'",
                "GRANT CREATE ON *.* TO '%s'@'%%'");
        for (int index = 0; index < unsafeGrants.size(); index++) {
            String runtimeUser = "rollout_ddl_runtime_" + index;
            String runtimeRole = "rollout_ddl_reader_" + index;
            String operatorUser = "rollout_ddl_operator_" + index;
            String operatorRole = "rollout_ddl_writer_" + index;
            dropAccount(root, runtimeUser, runtimeRole);
            dropAccount(root, operatorUser, operatorRole);
            try {
                root.execute("CREATE USER '" + runtimeUser + "'@'%'");
                root.execute("CREATE USER '" + operatorUser + "'@'%'");
                String grant = unsafeGrants.get(index);
                root.execute(grant.contains("`%s`")
                        ? grant.formatted(MYSQL.getDatabaseName(), runtimeUser)
                        : grant.formatted(runtimeUser));

                assertThatThrownBy(() -> executeScript(rootDataSource,
                        renderRolloutGrantTemplate(MYSQL.getDatabaseName(), runtimeUser, "%", runtimeRole, "%",
                                operatorUser, "%", operatorRole, "%")))
                        .hasStackTraceContaining("ROLLOUT_GRANT_DRIFT_RUNTIME_EFFECTIVE_PRIVILEGE");
            } finally {
                dropAccount(root, runtimeUser, runtimeRole);
                dropAccount(root, operatorUser, operatorRole);
            }
        }

        String runtimeUserWithSafeIdentity = "rollout_opddl_runtime";
        String runtimeRoleWithSafeIdentity = "rollout_opddl_reader";
        String unsafeOperatorUser = "rollout_opddl_login";
        String unsafeOperatorRole = "rollout_opddl_role";
        dropAccount(root, runtimeUserWithSafeIdentity, runtimeRoleWithSafeIdentity);
        dropAccount(root, unsafeOperatorUser, unsafeOperatorRole);
        try {
            root.execute("CREATE USER '" + runtimeUserWithSafeIdentity + "'@'%'");
            root.execute("CREATE USER '" + unsafeOperatorUser + "'@'%'");
            root.execute("GRANT DROP ON `" + MYSQL.getDatabaseName() + "`.* TO '"
                    + unsafeOperatorUser + "'@'%'");
            assertThatThrownBy(() -> executeScript(rootDataSource,
                    renderRolloutGrantTemplate(MYSQL.getDatabaseName(),
                            runtimeUserWithSafeIdentity, "%", runtimeRoleWithSafeIdentity, "%",
                            unsafeOperatorUser, "%", unsafeOperatorRole, "%")))
                    .hasStackTraceContaining("ROLLOUT_GRANT_DRIFT_OPERATOR_EFFECTIVE_PRIVILEGE");
        } finally {
            dropAccount(root, runtimeUserWithSafeIdentity, runtimeRoleWithSafeIdentity);
            dropAccount(root, unsafeOperatorUser, unsafeOperatorRole);
        }

        String sharedUser = "rollout_shared_login";
        String runtimeRole = "rollout_alias_reader";
        String operatorRole = "rollout_alias_writer";
        dropAccount(root, sharedUser, runtimeRole);
        root.execute("DROP ROLE IF EXISTS '" + operatorRole + "'@'%'");
        try {
            root.execute("CREATE USER '" + sharedUser + "'@'%'");
            assertThatThrownBy(() -> executeScript(rootDataSource,
                    renderRolloutGrantTemplate(MYSQL.getDatabaseName(), sharedUser, "%", runtimeRole, "%",
                            sharedUser, "%", operatorRole, "%")))
                    .hasStackTraceContaining("ROLLOUT_GRANT_DRIFT_IDENTITY_ALIAS");
        } finally {
            dropAccount(root, sharedUser, runtimeRole);
            root.execute("DROP ROLE IF EXISTS '" + operatorRole + "'@'%'");
        }

        String runtimeUser = "rollout_alias_runtime";
        String operatorUser = "rollout_alias_operator";
        String sharedRole = "rollout_shared_role";
        dropAccount(root, runtimeUser, sharedRole);
        root.execute("DROP USER IF EXISTS '" + operatorUser + "'@'%'");
        try {
            root.execute("CREATE USER '" + runtimeUser + "'@'%'");
            root.execute("CREATE USER '" + operatorUser + "'@'%'");
            assertThatThrownBy(() -> executeScript(rootDataSource,
                    renderRolloutGrantTemplate(MYSQL.getDatabaseName(), runtimeUser, "%", sharedRole, "%",
                            operatorUser, "%", sharedRole, "%")))
                    .hasStackTraceContaining("ROLLOUT_GRANT_DRIFT_IDENTITY_ALIAS");
        } finally {
            dropAccount(root, runtimeUser, sharedRole);
            root.execute("DROP USER IF EXISTS '" + operatorUser + "'@'%'");
        }
    }

    @Test
    void rolloutCheckpointGrantTemplateRejectsMandatoryRolesOutsideRoleEdges() throws IOException {
        TestDatabase database = newLegacyDatabase();
        runMigration(database.dataSource());
        DataSource rootDataSource = rootDataSource();
        JdbcTemplate root = new JdbcTemplate(rootDataSource);
        String runtimeUser = "rollout_mand_runtime";
        String runtimeRole = "rollout_mand_reader";
        String operatorUser = "rollout_mand_operator";
        String operatorRole = "rollout_mand_writer";
        String mandatoryRole = "rollout_mandatory";
        dropAccount(root, runtimeUser, runtimeRole);
        dropAccount(root, operatorUser, operatorRole);
        root.execute("DROP ROLE IF EXISTS '" + mandatoryRole + "'@'%'");
        try {
            root.execute("CREATE USER '" + runtimeUser + "'@'%'");
            root.execute("CREATE USER '" + operatorUser + "'@'%'");
            root.execute("CREATE ROLE '" + mandatoryRole + "'@'%'");
            root.execute("GRANT DROP ON `" + MYSQL.getDatabaseName() + "`.* TO '"
                    + mandatoryRole + "'@'%'");
            root.execute("SET GLOBAL mandatory_roles='" + mandatoryRole + "@%' ");

            assertThatThrownBy(() -> executeScript(rootDataSource,
                    renderRolloutGrantTemplate(MYSQL.getDatabaseName(), runtimeUser, "%", runtimeRole, "%",
                            operatorUser, "%", operatorRole, "%")))
                    .hasStackTraceContaining("ROLLOUT_GRANT_DRIFT_MANDATORY_ROLES");
        } finally {
            root.execute("SET GLOBAL mandatory_roles=''");
            dropAccount(root, runtimeUser, runtimeRole);
            dropAccount(root, operatorUser, operatorRole);
            root.execute("DROP ROLE IF EXISTS '" + mandatoryRole + "'@'%'");
        }
    }

    @Test
    void rolloutCheckpointGrantTemplateRejectsDynamicRoleAdministrationPrivilege() throws IOException {
        TestDatabase database = newLegacyDatabase();
        runMigration(database.dataSource());
        DataSource rootDataSource = rootDataSource();
        JdbcTemplate root = new JdbcTemplate(rootDataSource);
        String runtimeUser = "rollout_dyn_runtime";
        String runtimeRole = "rollout_dyn_reader";
        String operatorUser = "rollout_dyn_operator";
        String operatorRole = "rollout_dyn_writer";
        dropAccount(root, runtimeUser, runtimeRole);
        dropAccount(root, operatorUser, operatorRole);
        try {
            root.execute("CREATE USER '" + runtimeUser + "'@'%'");
            root.execute("CREATE USER '" + operatorUser + "'@'%'");
            root.execute("GRANT ROLE_ADMIN ON *.* TO '" + runtimeUser + "'@'%'");

            assertThatThrownBy(() -> executeScript(rootDataSource,
                    renderRolloutGrantTemplate(MYSQL.getDatabaseName(), runtimeUser, "%", runtimeRole, "%",
                            operatorUser, "%", operatorRole, "%")))
                    .hasStackTraceContaining("ROLLOUT_GRANT_DRIFT_DYNAMIC_PRIVILEGE");
        } finally {
            dropAccount(root, runtimeUser, runtimeRole);
            dropAccount(root, operatorUser, operatorRole);
        }
    }

    @Test
    void rolloutCheckpointGrantTemplateRejectsControlledRoleGrantedToThirdParty() throws IOException {
        TestDatabase database = newLegacyDatabase();
        runMigration(database.dataSource());
        DataSource rootDataSource = rootDataSource();
        JdbcTemplate root = new JdbcTemplate(rootDataSource);
        String runtimeUser = "rollout_edge_runtime";
        String runtimeRole = "rollout_edge_reader";
        String operatorUser = "rollout_edge_operator";
        String operatorRole = "rollout_edge_writer";
        String thirdParty = "rollout_edge_third";
        dropAccount(root, runtimeUser, runtimeRole);
        dropAccount(root, operatorUser, operatorRole);
        root.execute("DROP USER IF EXISTS '" + thirdParty + "'@'%'");
        try {
            root.execute("CREATE USER '" + runtimeUser + "'@'%'");
            root.execute("CREATE USER '" + operatorUser + "'@'%'");
            root.execute("CREATE USER '" + thirdParty + "'@'%'");
            root.execute("CREATE ROLE '" + operatorRole + "'@'%'");
            root.execute("GRANT '" + operatorRole + "'@'%' TO '" + thirdParty + "'@'%'");

            assertThatThrownBy(() -> executeScript(rootDataSource,
                    renderRolloutGrantTemplate(MYSQL.getDatabaseName(), runtimeUser, "%", runtimeRole, "%",
                            operatorUser, "%", operatorRole, "%")))
                    .hasStackTraceContaining("ROLLOUT_GRANT_DRIFT_CONTROLLED_ROLE_MEMBERSHIP");
        } finally {
            dropAccount(root, runtimeUser, runtimeRole);
            dropAccount(root, operatorUser, operatorRole);
            root.execute("DROP USER IF EXISTS '" + thirdParty + "'@'%'");
        }
    }

    @Test
    void rolloutCheckpointGrantTemplateRejectsAdminOptionOnIntendedOperatorEdge() throws IOException {
        TestDatabase database = newLegacyDatabase();
        runMigration(database.dataSource());
        DataSource rootDataSource = rootDataSource();
        JdbcTemplate root = new JdbcTemplate(rootDataSource);
        String runtimeUser = "rollout_admin_runtime";
        String runtimeRole = "rollout_admin_reader";
        String operatorUser = "rollout_admin_operator";
        String operatorRole = "rollout_admin_writer";
        dropAccount(root, runtimeUser, runtimeRole);
        dropAccount(root, operatorUser, operatorRole);
        try {
            root.execute("CREATE USER '" + runtimeUser + "'@'%'");
            root.execute("CREATE USER '" + operatorUser + "'@'%'");
            root.execute("CREATE ROLE '" + operatorRole + "'@'%'");
            root.execute("GRANT '" + operatorRole + "'@'%' TO '" + operatorUser
                    + "'@'%' WITH ADMIN OPTION");

            assertThatThrownBy(() -> executeScript(rootDataSource,
                    renderRolloutGrantTemplate(MYSQL.getDatabaseName(), runtimeUser, "%", runtimeRole, "%",
                            operatorUser, "%", operatorRole, "%")))
                    .hasStackTraceContaining("ROLLOUT_GRANT_DRIFT_CONTROLLED_ROLE_MEMBERSHIP");
        } finally {
            dropAccount(root, runtimeUser, runtimeRole);
            dropAccount(root, operatorUser, operatorRole);
        }
    }

    @Test
    void rolloutCheckpointGrantTemplateRejectsUnlockedRolePrincipal() throws IOException {
        TestDatabase database = newLegacyDatabase();
        runMigration(database.dataSource());
        DataSource rootDataSource = rootDataSource();
        JdbcTemplate root = new JdbcTemplate(rootDataSource);
        String runtimeUser = "rollout_lock_runtime";
        String runtimeRole = "rollout_lock_reader";
        String operatorUser = "rollout_lock_operator";
        String operatorRole = "rollout_lock_writer";
        dropAccount(root, runtimeUser, runtimeRole);
        dropAccount(root, operatorUser, operatorRole);
        try {
            root.execute("CREATE USER '" + runtimeUser + "'@'%'");
            root.execute("CREATE USER '" + operatorUser + "'@'%'");
            root.execute("CREATE ROLE '" + operatorRole + "'@'%'");
            root.execute("ALTER USER '" + operatorRole + "'@'%' ACCOUNT UNLOCK");

            assertThatThrownBy(() -> executeScript(rootDataSource,
                    renderRolloutGrantTemplate(MYSQL.getDatabaseName(), runtimeUser, "%", runtimeRole, "%",
                            operatorUser, "%", operatorRole, "%")))
                    .hasStackTraceContaining("ROLLOUT_GRANT_DRIFT_ROLE_LOGIN_ENABLED");
        } finally {
            dropAccount(root, runtimeUser, runtimeRole);
            dropAccount(root, operatorUser, operatorRole);
        }
    }

    @Test
    void immutableGrantTemplateAndExpandRunbookContainFailClosedOperationalControls() throws IOException {
        String grants = Files.readString(GRANTS);
        String runbook = Files.readString(EXPAND_RUNBOOK);

        assertThat(grants).contains("${APP_DB_NAME}", "${APP_DB_USER}", "${APP_DB_HOST}",
                "${IMMUTABLE_ROLE}", "${IMMUTABLE_ROLE_HOST}");
        assertThat(grants).containsIgnoringCase("CREATE ROLE IF NOT EXISTS");
        assertThat(grants).containsIgnoringCase("GRANT SELECT, INSERT");
        assertThat(grants).containsIgnoringCase("SET DEFAULT ROLE");
        assertThat(grants).contains("IMMUTABLE_GRANT_DRIFT_EFFECTIVE_MUTATION_PRIVILEGE");
        assertThat(grants).contains("information_schema.column_privileges");
        assertThat(grants).contains("article_revision", "article_moderation_attempt");
        assertThat(grants).doesNotContainIgnoringCase("REVOKE UPDATE, DELETE");
        assertThat(grants).doesNotContainIgnoringCase("identified by", "password=");
        assertThat(grants).doesNotContain("root@", "'root'");

        assertThat(runbook).contains("information_schema.innodb_trx",
                "performance_schema.metadata_locks", "information_schema.tables",
                "lock_wait_timeout", "ALGORITHM=INSTANT", "ALGORITHM=INPLACE",
                "LOCK=NONE", "backup", "maintenance window", "'article', 'message', 'tag'",
                "does not limit how long the DDL holds the lock");
        assertThat(runbook).contains("other application tables", "direct table-level grants");
    }

    private static void assertCompleteManifest(JdbcTemplate jdbc) {
        Map<String, List<ColumnContract>> newTables = new LinkedHashMap<>();
        newTables.put("article_draft", List.of(
                required("article_id", "bigint"), required("user_id", "bigint"),
                required("draft_version", "bigint"), required("title", "varchar(100)"),
                nullable("summary", "varchar(255)"), nullable("body_markdown", "mediumtext"),
                nullable("body_plain", "mediumtext"), nullable("cover", "varchar(255)"),
                required("tags_json", "json"), asciiRequired("content_hash", "char(64)"),
                required("created_at", "datetime(6)"), required("updated_at", "datetime(6)"),
                requiredDefault("lock_version", "bigint", "0")));
        newTables.put("article_revision", List.of(
                autoId(), required("article_id", "bigint"), required("revision_no", "bigint"),
                required("title", "varchar(100)"), nullable("summary", "varchar(255)"),
                nullable("body_markdown", "mediumtext"), nullable("body_plain", "mediumtext"),
                nullable("cover", "varchar(255)"), required("tags_json", "json"),
                asciiRequired("content_hash", "char(64)"), required("source_draft_version", "bigint"),
                required("created_by", "bigint"), required("created_at", "datetime(6)")));
        newTables.put("article_moderation_job", List.of(
                autoId(), required("article_id", "bigint"), required("revision_id", "bigint"),
                asciiRequired("content_hash", "char(64)"), required("state", "varchar(24)"),
                nullable("model_decision", "varchar(16)"), nullable("risk_score", "decimal(6,5)"),
                nullable("policy_hits_json", "json"), requiredDefault("attempt_count", "int", "0"),
                nullable("next_attempt_at", "datetime(6)"), nullable("lease_owner", "varchar(96)"),
                nullable("lease_until", "datetime(6)"), nullable("last_error", "varchar(500)"),
                nullable("reviewer_id", "bigint"), nullable("review_reason", "varchar(500)"),
                nullable("reviewed_at", "datetime(6)"), required("created_at", "datetime(6)"),
                required("updated_at", "datetime(6)"), requiredDefault("lock_version", "bigint", "0")));
        newTables.put("article_moderation_attempt", List.of(
                autoId(), required("job_id", "bigint"), required("attempt_no", "int"),
                nullable("provider", "varchar(32)"), nullable("model", "varchar(96)"),
                required("prompt_version", "varchar(32)"), asciiRequired("input_hash", "char(64)"),
                nullable("structured_output_json", "json"), required("latency_ms", "bigint"),
                nullable("token_usage_json", "json"), nullable("finish_reason", "varchar(32)"),
                nullable("error_code", "varchar(64)"), required("created_at", "datetime(6)")));
        newTables.put("article_revision_migration_issue", List.of(
                autoId(), required("article_id", "bigint"), required("issue_code", "varchar(64)"),
                asciiNullable("observed_hash", "char(64)"), required("details_json", "json"),
                required("detected_at", "datetime(6)"), nullable("resolved_at", "datetime(6)"),
                nullable("resolution_note", "varchar(500)")));
        newTables.put("domain_event_outbox", List.of(
                autoId(), required("event_id", "binary(16)"), required("aggregate_type", "varchar(64)"),
                required("aggregate_id", "bigint"), required("aggregate_version", "bigint"),
                required("lifecycle_epoch", "bigint"), required("event_type", "varchar(64)"),
                required("payload_version", "int"), required("payload_json", "json"),
                required("dedupe_key", "varchar(190)"), required("occurred_at", "datetime(6)"),
                requiredDefault("state", "varchar(16)", "PENDING"),
                requiredDefault("retry_count", "int", "0"), required("next_attempt_at", "datetime(6)"),
                nullable("lease_owner", "varchar(96)"), nullable("lease_until", "datetime(6)"),
                nullable("last_error", "varchar(500)"), required("created_at", "datetime(6)"),
                nullable("published_at", "datetime(6)"), nullable("failed_at", "datetime(6)"),
                nullable("dead_resolved_at", "datetime(6)"),
                nullable("dead_resolved_by", "varchar(96)"),
                nullable("dead_resolution", "varchar(32)")));
        newTables.put("consumer_inbox", List.of(
                required("consumer_name", "varchar(96)"), required("event_id", "binary(16)"),
                required("processed_at", "datetime(6)"), asciiRequired("result_hash", "char(64)")));
        newTables.put("projection_watermark", List.of(
                required("consumer_name", "varchar(96)"), required("aggregate_type", "varchar(64)"),
                required("aggregate_id", "bigint"), requiredDefault("last_applied_version", "bigint", "0"),
                requiredDefault("lifecycle_epoch", "bigint", "0"),
                requiredDefault("tombstone", "tinyint(1)", "0"), nullable("lease_owner", "varchar(96)"),
                nullable("lease_until", "datetime(6)"), required("updated_at", "datetime(6)")));
        newTables.put("article_revision_rollout_checkpoint", List.of(
                required("checkpoint_id", "tinyint"), required("mode", "varchar(24)"),
                required("schema_generation", "bigint"),
                required("minimum_binary_generation", "bigint"),
                asciiRequired("required_build_digest", "char(64)"),
                nullable("backfill_started_at", "datetime(6)"),
                asciiNullable("verified_build_digest", "char(64)"),
                asciiNullable("verified_fingerprint", "char(64)"),
                asciiNullable("verify_report_hash", "char(64)"),
                nullable("verified_at", "datetime(6)"),
                asciiNullable("sentinel_build_digest", "char(64)"),
                asciiNullable("sentinel_report_hash", "char(64)"),
                nullable("sentinel_verified_at", "datetime(6)"),
                requiredDefault("cutover_epoch", "bigint", "0"),
                required("updated_by", "varchar(96)"), required("updated_at", "datetime(6)"),
                requiredDefault("lock_version", "bigint", "0")));

        newTables.forEach((table, expected) -> assertThat(columns(jdbc, table))
                .as("columns of %s", table).containsExactlyElementsOf(expected));

        assertThat(column(jdbc, "article", "latest_revision_id")).isEqualTo(nullable("latest_revision_id", "bigint"));
        assertThat(column(jdbc, "article", "pending_revision_id")).isEqualTo(nullable("pending_revision_id", "bigint"));
        assertThat(column(jdbc, "article", "published_revision_id")).isEqualTo(nullable("published_revision_id", "bigint"));
        assertThat(column(jdbc, "article", "visibility_state")).isEqualTo(nullable("visibility_state", "varchar(24)"));
        assertThat(column(jdbc, "article", "review_state")).isEqualTo(nullable("review_state", "varchar(24)"));
        assertThat(column(jdbc, "article", "lifecycle_epoch")).isEqualTo(requiredDefault("lifecycle_epoch", "bigint", "1"));
        assertThat(column(jdbc, "article", "lock_version")).isEqualTo(requiredDefault("lock_version", "bigint", "0"));
        assertThat(column(jdbc, "article", "content")).isEqualTo(nullable("content", "mediumtext"));
        assertThat(column(jdbc, "tag", "name")).isEqualTo(exactRequired("name", "varchar(50)"));
        assertThat(column(jdbc, "message", "source_event_id")).isEqualTo(nullable("source_event_id", "binary(16)"));

        Map<String, IndexContract> indexes = Map.ofEntries(
                indexEntry("article", "uk_article_id_author", false, "id", "author_id"),
                indexEntry("article", "idx_article_latest_pointer", true, "latest_revision_id", "id"),
                indexEntry("article", "idx_article_pending_pointer", true, "pending_revision_id", "id"),
                indexEntry("article", "idx_article_published_pointer", true, "published_revision_id", "id"),
                indexEntry("tag", "uk_name", false, "name"),
                indexEntry("message", "uk_message_source_event", false, "source_event_id"),
                indexEntry("article_draft", "PRIMARY", false, "article_id"),
                indexEntry("article_draft", "uk_article_draft_owner", false, "article_id", "user_id"),
                indexEntry("article_revision", "PRIMARY", false, "id"),
                indexEntry("article_revision", "uk_article_revision_no", false, "article_id", "revision_no"),
                indexEntry("article_revision", "uk_article_revision_identity", false, "id", "article_id"),
                indexEntry("article_revision", "idx_article_revision_creator", true, "article_id", "created_by"),
                indexEntry("article_moderation_job", "PRIMARY", false, "id"),
                indexEntry("article_moderation_job", "uk_article_moderation_revision", false, "article_id", "revision_id"),
                indexEntry("article_moderation_job", "uk_article_moderation_identity", false, "id", "article_id"),
                indexEntry("article_moderation_job", "idx_moderation_revision_fk", true, "revision_id", "article_id"),
                indexEntry("article_moderation_job", "idx_moderation_queue", true, "state", "next_attempt_at", "id"),
                indexEntry("article_moderation_attempt", "PRIMARY", false, "id"),
                indexEntry("article_moderation_attempt", "uk_moderation_attempt", false, "job_id", "attempt_no"),
                indexEntry("article_revision_migration_issue", "PRIMARY", false, "id"),
                indexEntry("article_revision_migration_issue", "uk_revision_migration_issue", false, "article_id", "issue_code"),
                indexEntry("article_revision_migration_issue", "idx_revision_migration_unresolved", true, "resolved_at", "article_id"),
                indexEntry("article_revision_migration_issue", "idx_revision_migration_retention", true, "resolved_at", "id"),
                indexEntry("domain_event_outbox", "PRIMARY", false, "id"),
                indexEntry("domain_event_outbox", "uk_domain_event_id", false, "event_id"),
                indexEntry("domain_event_outbox", "uk_domain_event_dedupe", false, "dedupe_key"),
                indexEntry("domain_event_outbox", "idx_domain_outbox_dispatch", true, "state", "next_attempt_at", "id"),
                indexEntry("domain_event_outbox", "idx_domain_outbox_recovery", true, "state", "lease_until", "id"),
                indexEntry("domain_event_outbox", "idx_domain_outbox_published_retention", true, "state", "published_at", "id"),
                indexEntry("domain_event_outbox", "idx_domain_outbox_dead_retention", true, "state", "dead_resolved_at", "id"),
                indexEntry("consumer_inbox", "PRIMARY", false, "consumer_name", "event_id"),
                indexEntry("consumer_inbox", "idx_consumer_inbox_retention", true,
                        "processed_at", "consumer_name", "event_id"),
                indexEntry("projection_watermark", "PRIMARY", false, "consumer_name", "aggregate_type", "aggregate_id"),
                indexEntry("projection_watermark", "idx_projection_lease", true, "lease_until"),
                indexEntry("article_revision_rollout_checkpoint", "PRIMARY", false, "checkpoint_id"));
        indexes.forEach((qualifiedName, expected) -> {
            String[] parts = qualifiedName.split("\\.", 2);
            assertThat(index(jdbc, parts[0], parts[1])).as(qualifiedName).isEqualTo(expected);
        });

        Map<String, ForeignKeyContract> foreignKeys = Map.ofEntries(
                fkEntry("article_draft", "fk_article_draft_owner", "article",
                        "article_id", "id", "user_id", "author_id"),
                fkEntry("article_revision", "fk_article_revision_article", "article",
                        "article_id", "id"),
                fkEntry("article_revision", "fk_article_revision_creator", "article",
                        "article_id", "id", "created_by", "author_id"),
                fkEntry("article_moderation_job", "fk_moderation_revision", "article_revision",
                        "revision_id", "id", "article_id", "article_id"),
                fkEntry("article_moderation_attempt", "fk_attempt_job", "article_moderation_job",
                        "job_id", "id"),
                fkEntry("article_revision_migration_issue", "fk_revision_migration_article", "article",
                        "article_id", "id"),
                fkEntry("article", "fk_article_latest_revision", "article_revision",
                        "latest_revision_id", "id", "id", "article_id"),
                fkEntry("article", "fk_article_pending_revision", "article_revision",
                        "pending_revision_id", "id", "id", "article_id"),
                fkEntry("article", "fk_article_published_revision", "article_revision",
                        "published_revision_id", "id", "id", "article_id"));
        foreignKeys.forEach((qualifiedName, expected) -> {
            String[] parts = qualifiedName.split("\\.", 2);
            assertThat(foreignKey(jdbc, parts[0], parts[1])).as(qualifiedName).isEqualTo(expected);
        });

        assertThat(column(jdbc, "domain_event_outbox", "id").autoIncrement()).isTrue();
        assertThat(index(jdbc, "domain_event_outbox", "uk_domain_event_id").columns())
                .containsExactly("event_id");
        assertThat(foreignKey(jdbc, "article_draft", "fk_article_draft_owner").columns())
                .containsExactly(new ForeignKeyColumn("article_id", "article", "id"),
                        new ForeignKeyColumn("user_id", "article", "author_id"));
        assertThat(foreignKey(jdbc, "article", "fk_article_published_revision").columns())
                .containsExactly(new ForeignKeyColumn("published_revision_id", "article_revision", "id"),
                        new ForeignKeyColumn("id", "article_revision", "article_id"));
        assertThat(foreignKeys(jdbc).stream().noneMatch(fk -> fk.columns().stream()
                .anyMatch(column -> column.referencedColumn().equals("user_id")))).isTrue();

        newTables.keySet().forEach(table -> assertThat(jdbc.queryForMap("""
                        SELECT engine,table_collation FROM information_schema.tables
                        WHERE table_schema=DATABASE() AND table_name=?
                        """, table))
                .containsEntry("engine", "InnoDB")
                .containsEntry("table_collation", "utf8mb4_unicode_ci"));

        assertThat(checkConstraints(jdbc, "article_revision_rollout_checkpoint"))
                .containsExactly(new CheckConstraintContract(
                        "chk_article_revision_rollout_singleton", "checkpoint_id=1", true));
    }

    private static void assertCrossArticlePointerIsRejected(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO article(id,title,author_id) VALUES (101,'one',1001),(102,'two',1002)");
        jdbc.update("""
                INSERT INTO article_revision(article_id,revision_no,title,tags_json,content_hash,
                    source_draft_version,created_by,created_at)
                VALUES (101,1,'one',JSON_ARRAY(),REPEAT('a',64),1,1001,NOW(6)),
                       (102,1,'two',JSON_ARRAY(),REPEAT('b',64),1,1002,NOW(6))
                """);
        Long otherRevision = jdbc.queryForObject(
                "SELECT id FROM article_revision WHERE article_id=102", Long.class);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE article SET published_revision_id=? WHERE id=101", otherRevision))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    private static void assertTable(String className, String tableName) throws Exception {
        Class<?> type = Class.forName(className);
        assertThat(type.getAnnotation(TableName.class)).isNotNull();
        assertThat(type.getAnnotation(TableName.class).value()).isEqualTo(tableName);
    }

    private static void assertMapper(String className, boolean mutable) throws Exception {
        Class<?> mapper = Class.forName(className);
        assertThat(mapper.isInterface()).isTrue();
        assertThat(mapper.getAnnotation(Mapper.class)).isNotNull();
        assertThat(BaseMapper.class.isAssignableFrom(mapper)).isEqualTo(mutable);
    }

    private static TestDatabase newLegacyDatabase() {
        TestDatabase database = newEmptyDatabase();
        DataSource dataSource = database.dataSource();
        ResourceDatabasePopulator legacy = new ResourceDatabasePopulator(new ByteArrayResource("""
                CREATE TABLE article (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    title VARCHAR(100) NOT NULL,
                    summary VARCHAR(255) NULL,
                    content TEXT NULL COMMENT '内容',
                    author_id BIGINT NOT NULL,
                    view_count INT DEFAULT 0 NULL,
                    like_count INT DEFAULT 0 NULL,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP NULL,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP NULL ON UPDATE CURRENT_TIMESTAMP,
                    status TINYINT DEFAULT 1 NULL,
                    cover VARCHAR(255) NULL,
                    is_deleted TINYINT DEFAULT 0 NULL,
                    delete_time DATETIME NULL,
                    comment_count INT DEFAULT 0 NULL,
                    collect_count INT DEFAULT 0 NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                CREATE TABLE message (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    from_id BIGINT NOT NULL,
                    to_id BIGINT NOT NULL,
                    type TINYINT NOT NULL,
                    target_id BIGINT NULL,
                    content VARCHAR(500) NULL,
                    status TINYINT DEFAULT 0 NULL,
                    create_time DATETIME NULL,
                    INDEX idx_to_id_status(to_id,status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                CREATE TABLE tag (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(50) NOT NULL COMMENT '标签名',
                    article_count INT DEFAULT 0 NULL,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP NULL,
                    UNIQUE KEY uk_name(name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                CREATE TABLE article_tag (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    article_id BIGINT NOT NULL,
                    tag_id BIGINT NOT NULL,
                    UNIQUE KEY uk_article_tag(article_id,tag_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """.getBytes(StandardCharsets.UTF_8)));
        legacy.execute(dataSource);
        return database;
    }

    private static void createHistoricalOutbox20(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE domain_event_outbox (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    event_id BINARY(16) NOT NULL,
                    aggregate_type VARCHAR(64) NOT NULL,
                    aggregate_id BIGINT NOT NULL,
                    aggregate_version BIGINT NOT NULL,
                    lifecycle_epoch BIGINT NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    payload_version INT NOT NULL,
                    payload_json JSON NOT NULL,
                    dedupe_key VARCHAR(190) NOT NULL,
                    occurred_at DATETIME(6) NOT NULL,
                    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    retry_count INT NOT NULL DEFAULT 0,
                    next_attempt_at DATETIME(6) NOT NULL,
                    lease_owner VARCHAR(96) NULL,
                    lease_until DATETIME(6) NULL,
                    last_error VARCHAR(500) NULL,
                    created_at DATETIME(6) NOT NULL,
                    published_at DATETIME(6) NULL,
                    failed_at DATETIME(6) NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    private static TestDatabase newEmptyDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        ResourceDatabasePopulator reset = new ResourceDatabasePopulator(new ByteArrayResource("""
                SET FOREIGN_KEY_CHECKS=0;
                DROP TABLE IF EXISTS article_chunk,article_chunk_set,
                    article_chunk_parser_checkpoint,article_chunk_parser_generation,
                    projection_rebuild_item,projection_rebuild_job,projection_switch_fence,
                    projection_entity_manifest,projection_target_registry,
                    projection_consumer_event_type,projection_consumer_registry,
                    article_revision_rollout_checkpoint,
                    projection_watermark,consumer_inbox,domain_event_outbox,
                    article_revision_migration_issue,article_moderation_attempt,article_moderation_job,
                    article_draft,article_revision,recommendation_exposure,recommendation_event_outbox,
                    recommendation_profile_checkpoint,user_article_event,tag,sys_user,report,message,
                    like_record,follow,favorite_folder,favorite,comment,chat_msg,article_tag,article;
                SET FOREIGN_KEY_CHECKS=1;
                """.getBytes(StandardCharsets.UTF_8)));
        reset.execute(dataSource);
        return new TestDatabase(dataSource, new JdbcTemplate(dataSource));
    }

    private static void runMigration(DataSource dataSource) {
        new ResourceDatabasePopulator(new FileSystemResource(MIGRATION)).execute(dataSource);
    }

    private static void runMigrationPrefix(DataSource dataSource, String marker) {
        try {
            String script = Files.readString(MIGRATION);
            int markerIndex = script.indexOf(marker);
            assertThat(markerIndex).as("migration marker %s", marker).isGreaterThan(0);
            String prefix = script.substring(0, markerIndex);
            new ResourceDatabasePopulator(new ByteArrayResource(prefix.getBytes(StandardCharsets.UTF_8)))
                    .execute(dataSource);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static DataSource rootDataSource() {
        return new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }

    private static String renderGrantTemplate(
            String database, String user, String host, String role, String roleHost) throws IOException {
        return Files.readString(GRANTS)
                .replace("${APP_DB_NAME}", database)
                .replace("${APP_DB_USER}", user)
                .replace("${APP_DB_HOST}", host)
                .replace("${IMMUTABLE_ROLE}", role)
                .replace("${IMMUTABLE_ROLE_HOST}", roleHost);
    }

    private static String renderRolloutGrantTemplate(
            String database,
            String runtimeUser,
            String runtimeHost,
            String runtimeRole,
            String runtimeRoleHost,
            String operatorUser,
            String operatorHost,
            String operatorRole,
            String operatorRoleHost) throws IOException {
        return Files.readString(ROLLOUT_GRANTS)
                .replace("${APP_DB_NAME}", database)
                .replace("${ROLLOUT_RUNTIME_USER}", runtimeUser)
                .replace("${ROLLOUT_RUNTIME_HOST}", runtimeHost)
                .replace("${ROLLOUT_RUNTIME_ROLE}", runtimeRole)
                .replace("${ROLLOUT_RUNTIME_ROLE_HOST}", runtimeRoleHost)
                .replace("${ROLLOUT_OPERATOR_USER}", operatorUser)
                .replace("${ROLLOUT_OPERATOR_HOST}", operatorHost)
                .replace("${ROLLOUT_OPERATOR_ROLE}", operatorRole)
                .replace("${ROLLOUT_OPERATOR_ROLE_HOST}", operatorRoleHost);
    }

    private static void executeScript(DataSource dataSource, String sql) {
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);
    }

    private static void dropAccount(JdbcTemplate root, String user, String role) {
        root.execute("DROP USER IF EXISTS '" + user + "'@'%'");
        root.execute("DROP ROLE IF EXISTS '" + role + "'@'%'");
    }

    private static void assertMutationDenied(SqlMutation mutation) {
        assertThatThrownBy(mutation::execute)
                .hasRootCauseInstanceOf(SQLException.class)
                .hasStackTraceContaining("command denied");
    }

    private static List<ColumnContract> columns(JdbcTemplate jdbc, String table) {
        return jdbc.query("""
                        SELECT column_name,column_type,is_nullable,column_default,extra,character_set_name,collation_name
                        FROM information_schema.columns
                        WHERE table_schema=DATABASE() AND table_name=?
                        ORDER BY ordinal_position
                """, (rs, row) -> new ColumnContract(rs.getString(1), rs.getString(2),
                        "YES".equals(rs.getString(3)), rs.getString(4),
                        rs.getString(5).toLowerCase(), rs.getString(6), rs.getString(7)), table);
    }

    private static ColumnContract column(JdbcTemplate jdbc, String table, String name) {
        return columns(jdbc, table).stream().filter(column -> column.name().equals(name)).findFirst().orElse(null);
    }

    private static String columnComment(JdbcTemplate jdbc, String table, String name) {
        return jdbc.queryForObject("""
                SELECT column_comment FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                """, String.class, table, name);
    }

    private static IndexContract index(JdbcTemplate jdbc, String table, String name) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT non_unique,column_name,sub_part
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=? AND index_name=?
                ORDER BY seq_in_index
                """, table, name);
        if (rows.isEmpty()) {
            return null;
        }
        return new IndexContract(((Number) rows.getFirst().get("non_unique")).intValue() != 0,
                rows.stream().map(row -> (String) row.get("column_name")).toList(),
                rows.stream().map(row -> row.get("sub_part") == null ? null
                        : ((Number) row.get("sub_part")).intValue()).toList());
    }

    private static ForeignKeyContract foreignKey(JdbcTemplate jdbc, String table, String name) {
        return foreignKeys(jdbc).stream()
                .filter(fk -> fk.table().equals(table) && fk.name().equals(name))
                .findFirst().orElse(null);
    }

    private static List<ForeignKeyContract> foreignKeys(JdbcTemplate jdbc) {
        List<Map<String, Object>> constraints = jdbc.queryForList("""
                SELECT table_name,constraint_name,referenced_table_name,update_rule,delete_rule
                FROM information_schema.referential_constraints
                WHERE constraint_schema=DATABASE()
                ORDER BY table_name,constraint_name
                """);
        return constraints.stream().map(constraint -> {
            String table = (String) constraint.get("table_name");
            String name = (String) constraint.get("constraint_name");
            List<ForeignKeyColumn> columns = jdbc.query("""
                    SELECT column_name,referenced_table_name,referenced_column_name
                    FROM information_schema.key_column_usage
                    WHERE constraint_schema=DATABASE() AND table_name=? AND constraint_name=?
                    ORDER BY ordinal_position
                    """, (rs, row) -> new ForeignKeyColumn(rs.getString(1), rs.getString(2), rs.getString(3)),
                    table, name);
            return new ForeignKeyContract(table, name, (String) constraint.get("referenced_table_name"),
                    (String) constraint.get("update_rule"), (String) constraint.get("delete_rule"), columns);
        }).toList();
    }

    private static String metadataFingerprint(JdbcTemplate jdbc) {
        List<String> columns = jdbc.queryForList("""
                SELECT CONCAT_WS('|','C',table_name,column_name,column_type,is_nullable,
                    COALESCE(column_default,'<NULL>'),extra,COALESCE(character_set_name,''),
                    COALESCE(collation_name,''),column_comment)
                FROM information_schema.columns
                WHERE table_schema=DATABASE()
                ORDER BY table_name,ordinal_position
                """, String.class);
        List<String> indexes = jdbc.queryForList("""
                SELECT CONCAT_WS('|','I',table_name,index_name,non_unique,seq_in_index,column_name,COALESCE(sub_part,''))
                FROM information_schema.statistics
                WHERE table_schema=DATABASE()
                ORDER BY table_name,index_name,seq_in_index
                """, String.class);
        List<String> fks = jdbc.queryForList("""
                SELECT CONCAT_WS('|','F',k.table_name,k.constraint_name,k.ordinal_position,k.column_name,
                    k.referenced_table_name,k.referenced_column_name,r.update_rule,r.delete_rule)
                FROM information_schema.key_column_usage k
                JOIN information_schema.referential_constraints r
                  ON r.constraint_schema=k.constraint_schema AND r.table_name=k.table_name
                 AND r.constraint_name=k.constraint_name
                WHERE k.constraint_schema=DATABASE() AND k.referenced_table_name IS NOT NULL
                ORDER BY k.table_name,k.constraint_name,k.ordinal_position
                """, String.class);
        List<String> checks = checkFingerprint(jdbc, null);
        return String.join("\n", columns) + "\n" + String.join("\n", indexes) + "\n"
                + String.join("\n", fks) + "\n" + String.join("\n", checks);
    }

    private static String stageBMetadataFingerprint(JdbcTemplate jdbc) {
        String tables = "'article_draft','article_revision','article_moderation_job'," +
                "'article_moderation_attempt','article_revision_migration_issue'," +
                "'domain_event_outbox','consumer_inbox','projection_watermark'," +
                "'article_revision_rollout_checkpoint'";
        List<String> columns = jdbc.queryForList("""
                SELECT CONCAT_WS('|','C',table_name,column_name,column_type,is_nullable,
                    COALESCE(column_default,'<NULL>'),extra,COALESCE(character_set_name,''),
                    COALESCE(collation_name,''),column_comment)
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND (
                    table_name IN (%s) OR
                    (table_name='article' AND column_name IN ('latest_revision_id','pending_revision_id',
                        'published_revision_id','visibility_state','review_state','lifecycle_epoch','lock_version',
                        'content')) OR
                    (table_name='tag' AND column_name='name') OR
                    (table_name='message' AND column_name='source_event_id'))
                ORDER BY table_name,ordinal_position
                """.formatted(tables), String.class);
        List<String> indexes = jdbc.queryForList("""
                SELECT CONCAT_WS('|','I',table_name,index_name,non_unique,seq_in_index,column_name,COALESCE(sub_part,''))
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND (
                    table_name IN (%s) OR index_name IN ('uk_article_id_author','idx_article_latest_pointer',
                        'idx_article_pending_pointer','idx_article_published_pointer','uk_message_source_event') OR
                    (table_name='tag' AND index_name='uk_name'))
                ORDER BY table_name,index_name,seq_in_index
                """.formatted(tables), String.class);
        List<String> fks = jdbc.queryForList("""
                SELECT CONCAT_WS('|','F',k.table_name,k.constraint_name,k.ordinal_position,k.column_name,
                    k.referenced_table_name,k.referenced_column_name,r.update_rule,r.delete_rule)
                FROM information_schema.key_column_usage k
                JOIN information_schema.referential_constraints r
                  ON r.constraint_schema=k.constraint_schema AND r.table_name=k.table_name
                 AND r.constraint_name=k.constraint_name
                WHERE k.constraint_schema=DATABASE() AND (k.table_name IN (%s) OR k.table_name='article')
                ORDER BY k.table_name,k.constraint_name,k.ordinal_position
                """.formatted(tables), String.class);
        List<String> checks = checkFingerprint(jdbc, "article_revision_rollout_checkpoint");
        return String.join("\n", columns) + "\n" + String.join("\n", indexes) + "\n"
                + String.join("\n", fks) + "\n" + String.join("\n", checks);
    }

    private static List<CheckConstraintContract> checkConstraints(JdbcTemplate jdbc, String table) {
        return jdbc.query("""
                SELECT tc.constraint_name,cc.check_clause,tc.enforced
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_schema=tc.constraint_schema
                 AND cc.constraint_name=tc.constraint_name
                WHERE tc.constraint_schema=DATABASE() AND tc.table_name=?
                  AND tc.constraint_type='CHECK'
                ORDER BY tc.constraint_name
                """, (rs, row) -> new CheckConstraintContract(
                        rs.getString(1), normalizeCheckClause(rs.getString(2)), "YES".equals(rs.getString(3))), table);
    }

    private static List<String> checkFingerprint(JdbcTemplate jdbc, String onlyTable) {
        String tablePredicate = onlyTable == null ? "" : " AND tc.table_name='" + onlyTable + "'";
        return jdbc.queryForList("""
                SELECT CONCAT_WS('|','Ck',tc.table_name,tc.constraint_name,cc.check_clause,tc.enforced)
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_schema=tc.constraint_schema
                 AND cc.constraint_name=tc.constraint_name
                WHERE tc.constraint_schema=DATABASE() AND tc.constraint_type='CHECK'%s
                ORDER BY tc.table_name,tc.constraint_name
                """.formatted(tablePredicate), String.class);
    }

    private static String normalizeCheckClause(String clause) {
        return clause.toLowerCase().replace("`", "").replace(" ", "")
                .replace("(", "").replace(")", "");
    }

    private static Map<String, Long> tableCounts(JdbcTemplate jdbc) {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_type='BASE TABLE'
                ORDER BY table_name
                """, String.class);
        Map<String, Long> counts = new LinkedHashMap<>();
        tables.forEach(table -> counts.put(table,
                jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class)));
        return counts;
    }

    private static ColumnContract autoId() {
        return new ColumnContract("id", "bigint", false, null, "auto_increment", null, null);
    }

    private static ColumnContract required(String name, String type) {
        return new ColumnContract(name, type, false, null, "", charset(type), collation(type));
    }

    private static ColumnContract nullable(String name, String type) {
        return new ColumnContract(name, type, true, null, "", charset(type), collation(type));
    }

    private static ColumnContract requiredDefault(String name, String type, String defaultValue) {
        return new ColumnContract(name, type, false, defaultValue, "", charset(type), collation(type));
    }

    private static ColumnContract asciiRequired(String name, String type) {
        return new ColumnContract(name, type, false, null, "", "ascii", "ascii_bin");
    }

    private static ColumnContract asciiNullable(String name, String type) {
        return new ColumnContract(name, type, true, null, "", "ascii", "ascii_bin");
    }

    private static ColumnContract exactRequired(String name, String type) {
        return new ColumnContract(name, type, false, null, "", "utf8mb4", "utf8mb4_0900_bin");
    }

    private static String charset(String type) {
        return type.startsWith("varchar") || type.endsWith("text") || type.startsWith("char") ? "utf8mb4" : null;
    }

    private static String collation(String type) {
        return charset(type) == null ? null : "utf8mb4_unicode_ci";
    }

    private static Map.Entry<String, IndexContract> indexEntry(
            String table, String name, boolean nonUnique, String... columns) {
        return Map.entry(table + "." + name,
                new IndexContract(nonUnique, List.of(columns), java.util.Collections.nCopies(columns.length, null)));
    }

    private static Map.Entry<String, ForeignKeyContract> fkEntry(
            String table, String name, String referencedTable, String... childReferencedPairs) {
        java.util.ArrayList<ForeignKeyColumn> columns = new java.util.ArrayList<>();
        for (int index = 0; index < childReferencedPairs.length; index += 2) {
            columns.add(new ForeignKeyColumn(childReferencedPairs[index], referencedTable,
                    childReferencedPairs[index + 1]));
        }
        return Map.entry(table + "." + name,
                new ForeignKeyContract(table, name, referencedTable, "NO ACTION", "RESTRICT", columns));
    }

    private record TestDatabase(DataSource dataSource, JdbcTemplate jdbc) {
    }

    private record ColumnContract(String name, String type, boolean nullable, String defaultValue,
                                  String extra, String charset, String collation) {
        boolean autoIncrement() {
            return "auto_increment".equals(extra);
        }
    }

    private record IndexContract(boolean nonUnique, List<String> columns, List<Integer> prefixes) {
    }

    private record CheckConstraintContract(String name, String clause, boolean enforced) {
    }

    private record ForeignKeyColumn(String childColumn, String referencedTable, String referencedColumn) {
    }

    private record ForeignKeyContract(String table, String name, String referencedTable,
                                      String updateRule, String deleteRule, List<ForeignKeyColumn> columns) {
    }

    @FunctionalInterface
    private interface SqlMutation {
        void execute();
    }
}
