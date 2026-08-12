package cumt.zongzuo.community.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = false)
class AgentMemorySchemaIntegrationTest {

    private static final List<String> TABLES = List.of("agent_memory_item",
            "agent_memory_version", "agent_memory_source", "agent_memory_projection",
            "agent_memory_setting");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void memoryMigrationIsIdempotentAndOwnerScoped() {
        DataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(),
                MYSQL.getUsername(), MYSQL.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TABLES.reversed().forEach(table -> jdbc.execute("DROP TABLE IF EXISTS " + table));
        jdbc.execute("DROP TABLE IF EXISTS agent_answer_citation");
        jdbc.execute("DROP TABLE IF EXISTS agent_retrieval_hit");
        jdbc.execute("DROP TABLE IF EXISTS agent_tool_call");
        jdbc.execute("DROP TABLE IF EXISTS agent_message");
        jdbc.execute("DROP TABLE IF EXISTS agent_turn");
        jdbc.execute("DROP TABLE IF EXISTS agent_episode");
        jdbc.execute("DROP TABLE IF EXISTS agent_conversation");
        jdbc.execute("DROP TABLE IF EXISTS agent_run_guard");
        jdbc.execute("DROP TABLE IF EXISTS agent_profile");
        jdbc.execute("DROP TABLE IF EXISTS sys_user");
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        migrate(dataSource, "docs/database/migrations/2026-08-12-stage-d-agent-conversations.sql");
        migrate(dataSource, "docs/database/migrations/2026-08-12-stage-e-agent-memory.sql");
        String first = fingerprint(jdbc);
        migrate(dataSource, "docs/database/migrations/2026-08-12-stage-e-agent-memory.sql");

        assertThat(fingerprint(jdbc)).isEqualTo(first);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name LIKE 'agent_memory_%'
                ORDER BY table_name
                """, String.class)).containsExactlyElementsOf(TABLES.stream().sorted().toList());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.referential_constraints
                WHERE constraint_schema=DATABASE() AND table_name LIKE 'agent_memory_%'
                """, Integer.class)).isGreaterThanOrEqualTo(8);
        assertThat(foreignKeyColumns(jdbc, "fk_agent_memory_current_version"))
                .containsExactly("current_version_id", "id", "user_id");
        assertThat(foreignKeyColumns(jdbc, "fk_agent_memory_source_version"))
                .containsExactly("memory_version_id", "memory_id", "user_id");
    }

    private static void migrate(DataSource dataSource, String path) {
        new ResourceDatabasePopulator(new FileSystemResource(path)).execute(dataSource);
    }

    private static String fingerprint(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT SHA2(GROUP_CONCAT(CONCAT_WS(':',table_name,column_name,column_type,
                    is_nullable,COALESCE(column_default,'NULL'),ordinal_position)
                    ORDER BY table_name,ordinal_position SEPARATOR '|'),256)
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name LIKE 'agent_memory_%'
                """, String.class);
    }

    private static List<String> foreignKeyColumns(JdbcTemplate jdbc, String constraint) {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.key_column_usage
                WHERE constraint_schema=DATABASE() AND constraint_name=?
                ORDER BY ordinal_position
                """, String.class, constraint);
    }
}
