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
class AgentConversationSchemaIntegrationTest {

    private static final List<String> TABLES = List.of("agent_profile", "agent_run_guard",
            "agent_conversation", "agent_episode", "agent_turn", "agent_message",
            "agent_tool_call", "agent_retrieval_hit", "agent_answer_citation");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void forwardMigrationIsIdempotentAndBuildsTheOwnerScopedTruthGraph() {
        DataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(),
                MYSQL.getUsername(), MYSQL.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TABLES.reversed().forEach(table -> jdbc.execute("DROP TABLE IF EXISTS " + table));
        jdbc.execute("DROP TABLE IF EXISTS sys_user");
        jdbc.execute("""
                CREATE TABLE sys_user(id BIGINT PRIMARY KEY) ENGINE=InnoDB
                  DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

        migrate(dataSource);
        migrateWebSearch(dataSource);
        String first = fingerprint(jdbc);
        migrate(dataSource);
        migrateWebSearch(dataSource);

        assertThat(fingerprint(jdbc)).isEqualTo(first);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name LIKE 'agent\\_%'
                ORDER BY table_name
                """, String.class)).containsExactlyElementsOf(TABLES.stream().sorted().toList());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.referential_constraints
                WHERE constraint_schema=DATABASE() AND table_name LIKE 'agent\\_%'
                """, Integer.class)).isGreaterThanOrEqualTo(10);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND
                  ((table_name='agent_conversation' AND column_name='web_search_enabled') OR
                   (table_name='agent_turn' AND column_name='web_search_enabled'))
                """, Integer.class)).isEqualTo(2);
    }

    private static void migrateWebSearch(DataSource dataSource) {
        new ResourceDatabasePopulator(new FileSystemResource(
                "docs/database/migrations/2026-08-13-agent-web-search.sql"))
                .execute(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        new ResourceDatabasePopulator(new FileSystemResource(
                "docs/database/migrations/2026-08-12-stage-d-agent-conversations.sql"))
                .execute(dataSource);
    }

    private static String fingerprint(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT SHA2(GROUP_CONCAT(CONCAT_WS(':',table_name,column_name,column_type,
                    is_nullable,COALESCE(column_default,'NULL'),ordinal_position)
                    ORDER BY table_name,ordinal_position SEPARATOR '|'),256)
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name LIKE 'agent\\_%'
                """, String.class);
    }
}
