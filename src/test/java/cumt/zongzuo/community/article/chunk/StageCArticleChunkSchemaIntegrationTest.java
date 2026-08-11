package cumt.zongzuo.community.article.chunk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = false)
@Execution(ExecutionMode.SAME_THREAD)
class StageCArticleChunkSchemaIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/database/migrations/2026-08-12-stage-c-article-chunks.sql");
    private static final List<String> TABLES = List.of(
            "article_chunk_parser_generation", "article_chunk_parser_checkpoint",
            "article_chunk_set", "article_chunk");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void freshAndForwardSchemasConvergeAndMigrationIsIdempotent() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new FileSystemResource("script.sql")).execute(dataSource);
        String fresh = fingerprint(jdbc);

        dropChunkTables(jdbc);
        executeMigration(dataSource);
        assertThat(fingerprint(jdbc)).isEqualTo(fresh);
        executeMigration(dataSource);
        assertThat(fingerprint(jdbc)).isEqualTo(fresh);
    }

    @Test
    void forwardMigrationRejectsUnreviewedShapeDrift() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        dropChunkTables(jdbc);
        executeMigration(dataSource);
        jdbc.execute("ALTER TABLE article_chunk ADD COLUMN unreviewed_drift INT NULL");

        assertThatThrownBy(() -> executeMigration(dataSource)).rootCause()
                .hasMessageContaining("SCHEMA_DRIFT_stage_c_article_chunk_columns");
    }

    private static String fingerprint(JdbcTemplate jdbc) {
        return String.join("\n", jdbc.queryForList("""
                SELECT CONCAT(table_name,':',ordinal_position,':',column_name,':',column_type,
                              ':',is_nullable,':',COALESCE(column_default,'<NULL>'),
                              ':',COALESCE(collation_name,'<NULL>'))
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name IN (
                  'article_chunk_parser_generation','article_chunk_parser_checkpoint',
                  'article_chunk_set','article_chunk')
                ORDER BY table_name,ordinal_position
                """, String.class));
    }

    private static void executeMigration(DataSource dataSource) {
        new ResourceDatabasePopulator(new FileSystemResource(MIGRATION)).execute(dataSource);
    }

    private static void dropChunkTables(JdbcTemplate jdbc) {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : List.of("article_chunk", "article_chunk_set",
                "article_chunk_parser_checkpoint", "article_chunk_parser_generation")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }
}
