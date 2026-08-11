package cumt.zongzuo.community.event.projection;

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
class StageCProjectionSchemaIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/database/migrations/2026-08-12-stage-c-projection-control-plane.sql");
    private static final List<String> TABLES = List.of(
            "projection_consumer_registry",
            "projection_consumer_event_type",
            "projection_target_registry",
            "projection_entity_manifest",
            "projection_rebuild_job",
            "projection_rebuild_item",
            "projection_switch_fence");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void freshAndForwardSchemasConvergeAndSeedTheExactConsumerManifest() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new FileSystemResource("script.sql")).execute(dataSource);

        assertControlPlane(jdbc);
        String freshFingerprint = fingerprint(jdbc);

        dropControlPlane(jdbc);
        new ResourceDatabasePopulator(new FileSystemResource(MIGRATION)).execute(dataSource);
        assertControlPlane(jdbc);
        assertThat(fingerprint(jdbc)).isEqualTo(freshFingerprint);

        new ResourceDatabasePopulator(new FileSystemResource(MIGRATION)).execute(dataSource);
        assertControlPlane(jdbc);
        assertThat(fingerprint(jdbc)).isEqualTo(freshFingerprint);
    }

    @Test
    void forwardMigrationFailsClosedOnAnExtraControlPlaneColumn() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        dropControlPlane(jdbc);
        new ResourceDatabasePopulator(new FileSystemResource(MIGRATION)).execute(dataSource);
        jdbc.execute("ALTER TABLE projection_consumer_registry ADD COLUMN unreviewed_drift INT NULL");

        assertThatThrownBy(() -> new ResourceDatabasePopulator(new FileSystemResource(MIGRATION))
                .execute(dataSource))
                .rootCause()
                .hasMessageContaining("SCHEMA_DRIFT_stage_c_projection_columns");
    }

    private static void assertControlPlane(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name LIKE 'projection_%'
                  AND table_name <> 'projection_watermark'
                ORDER BY table_name
                """, String.class)).containsExactlyElementsOf(TABLES.stream().sorted().toList());

        assertThat(jdbc.queryForList("""
                SELECT CONCAT(consumer_name, ':', aggregate_type, ':', state, ':', proof_mode,
                              ':', required_for_retention)
                FROM projection_consumer_registry ORDER BY consumer_name
                """, String.class)).containsExactly(
                "article-chunk-current-pointer:ARTICLE:DISABLED:WATERMARK:0",
                "article-chunk-elasticsearch:ARTICLE_CHUNK_SET:DISABLED:TARGET_MANIFEST:0",
                "article-chunk-milvus:ARTICLE_CHUNK_SET:DISABLED:TARGET_MANIFEST:0",
                "article-search-current-pointer:ARTICLE:ACTIVE:WATERMARK:1");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM projection_consumer_event_type", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.check_constraints
                WHERE constraint_schema=DATABASE()
                  AND constraint_name='chk_projection_consumer_required_state'
                """, Integer.class)).isEqualTo(1);
    }

    private static String fingerprint(JdbcTemplate jdbc) {
        return String.join("\n", jdbc.queryForList("""
                SELECT CONCAT(table_name, ':', ordinal_position, ':', column_name, ':', column_type,
                              ':', is_nullable, ':', COALESCE(column_default, '<NULL>'),
                              ':', COALESCE(collation_name, '<NULL>'))
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name IN (
                  'projection_consumer_registry','projection_consumer_event_type',
                  'projection_target_registry','projection_entity_manifest',
                  'projection_rebuild_job','projection_rebuild_item','projection_switch_fence')
                ORDER BY table_name, ordinal_position
                """, String.class));
    }

    private static void dropControlPlane(JdbcTemplate jdbc) {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : TABLES.reversed()) {
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
