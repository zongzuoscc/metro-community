package cumt.zongzuo.community.ai.agent.memory;

import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/** 仅供显式启用的真实 MySQL 测试复用；调用者负责创建和最后删除专属 schema。 */
public final class AgeMemLiveDatabaseSupport {
    public final JdbcTemplate jdbc;
    public final AgentMemoryMapper mapper;
    public final AgentMemoryManagementService management;
    public final AgentMemoryToolService tools;
    public final DataSourceTransactionManager transactions;

    public AgeMemLiveDatabaseSupport() throws Exception {
        var dataSource = new DriverManagerDataSource(System.getenv("AGEMEM_MYSQL_URL"),
                System.getenv("AGEMEM_MYSQL_USER"), System.getenv("AGEMEM_MYSQL_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (schema == null || !(schema.startsWith("agemem_verify_") || schema.startsWith("agemem_test_")))
            throw new IllegalArgumentException("Live tests require their own agemem_verify_* schema");
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_user(id BIGINT PRIMARY KEY, account_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE') ENGINE=InnoDB");
        migrate(dataSource, "2026-08-12-stage-d-agent-conversations.sql");
        migrate(dataSource, "2026-08-12-stage-e-agent-memory.sql");
        migrate(dataSource, "2026-11-08-agemem-memory-tools.sql");
        migrate(dataSource, "2026-11-08-agemem-memory-tools.sql");
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentMemoryMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AgentMemoryMapper.class);
        transactions = new DataSourceTransactionManager(dataSource);
        management = new AgentMemoryManagementService(mapper,
                new AgentMemoryRecallService(mapper, mock(ObjectProvider.class)), transactions, new AgentMemorySafetyPolicy());
        tools = new AgentMemoryToolService(mapper, management, transactions, new AgentMemorySafetyPolicy());
    }

    /** 重置专属测试 schema，创建真实运行租约和已接纳的 USER 消息。 */
    public void seed(UUID runId) {
        for (String table : List.of("agent_memory_projection", "agent_memory_source")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE agent_memory_item SET current_version_id=NULL");
        for (String table : List.of("agent_memory_version", "agent_memory_item", "agent_memory_setting")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE agent_conversation SET last_message_id=NULL");
        for (String table : List.of("agent_message", "agent_turn", "agent_episode", "agent_conversation", "agent_run_guard", "sys_user"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO sys_user(id) VALUES(9),(10)");
        jdbc.update("""
                INSERT INTO agent_conversation(id,user_id,memory_epoch,created_at,updated_at)
                VALUES(7,9,1,NOW(6),NOW(6)),(8,10,1,NOW(6),NOW(6))
                """);
        jdbc.update("""
                INSERT INTO agent_episode(id,user_id,conversation_id,episode_no,state,opened_at,created_at,updated_at)
                VALUES(11,9,7,1,'ACTIVE',NOW(6),NOW(6),NOW(6))
                """);
        jdbc.update("""
                INSERT INTO agent_run_guard(user_id,active_run_id,active_run_type,run_fence,lease_until,updated_at)
                VALUES(9,?,'PERSISTENT',6,DATE_ADD(NOW(6),INTERVAL 10 MINUTE),NOW(6))
                """, uuid(runId));
        jdbc.update("""
                INSERT INTO agent_turn(id,user_id,conversation_id,episode_id,run_id,client_request_id,request_hash,
                task_type,page_context_json,grounding_mode,state,run_fence,lease_until,created_at)
                VALUES(21,9,7,11,?,?,'test','QA','{}','GROUNDED','RUNNING',6,DATE_ADD(NOW(6),INTERVAL 10 MINUTE),NOW(6))
                """, uuid(runId), uuid(UUID.randomUUID()));
        jdbc.update("""
                INSERT INTO agent_message(id,user_id,turn_id,conversation_id,episode_id,role,state,content,content_hash,created_at)
                VALUES(22,9,21,7,11,'USER','FINAL','回答时先给结论，之后改成回答时分步骤解释，忘掉原来的回答习惯。','test',NOW(6))
                """);
        mapper.ensureSetting(9);
        mapper.ensureSetting(10);
    }

    public void setQuestion(String question) {
        jdbc.update("UPDATE agent_message SET content=? WHERE id=22 AND user_id=9", question);
    }

    private static void migrate(javax.sql.DataSource dataSource, String name) {
        new ResourceDatabasePopulator(new FileSystemResource("docs/database/migrations/" + name)).execute(dataSource);
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }
}
