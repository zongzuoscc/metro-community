package cumt.zongzuo.community.ai.agent.memory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 显式设置 AGEMEM_MYSQL_URL/USER/PASSWORD 才执行，URL 必须指向本项目独立的 agemem_verify_*（或 agemem_test_*）schema。
 * 使用真实事务、MyBatis SQL 与迁移，不启动整套 Testcontainers，不访问业务 schema。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "AGEMEM_MYSQL_URL", matches = ".+")
class AgentMemoryToolMysqlTest {
    private JdbcTemplate jdbc;
    private AgentMemoryMapper mapper;
    private AgentMemoryManagementService management;
    private AgentMemoryToolService tools;
    private DataSourceTransactionManager transactions;
    private final UUID runId = UUID.randomUUID();

    private AgeMemLiveDatabaseSupport database;

    @BeforeAll void initialize() throws Exception {
        database = new AgeMemLiveDatabaseSupport();
        jdbc = database.jdbc;
        mapper = database.mapper;
        management = database.management;
        tools = database.tools;
        transactions = database.transactions;
    }

    @BeforeEach void seed() { database.seed(runId); }

    @Test void oneTurnCanCreateMultipleSourcedVersionsAndDeleteWithoutWaitingForIndex() {
        var first = tools.add(9, runId, 1, "PREFERENCE", "回答时先给结论", "回答时先给结论");
        assertThat(first.beforeEpoch()).isEqualTo(1);
        assertThat(first.afterEpoch()).isEqualTo(2);
        assertThat(first.view().sourceType()).isEqualTo("CONVERSATION");
        assertThat(mapper.projectionState(first.view().id(), 9)).isEqualTo("PENDING");
        var duplicate = tools.add(9, runId, 2, "PREFERENCE", "回答时先给结论", "回答时先给结论");
        assertThat(duplicate.status()).isEqualTo("ALREADY_EXISTS");
        assertThat(duplicate.afterEpoch()).isEqualTo(2);

        var second = tools.update(9, runId, 2, first.view().id(), 1, "回答时分步骤解释", "回答时分步骤解释");
        assertThat(second.view().version()).isEqualTo(2);
        assertThat(second.afterEpoch()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT memory_version_id) FROM agent_memory_source WHERE source_message_id=22", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT state FROM agent_memory_projection ORDER BY memory_version_id", String.class))
                .containsExactly("DELETING", "PENDING");
        var deleted = tools.delete(9, runId, 3, first.view().id(), 2, "忘掉原来的回答习惯");
        assertThat(deleted.afterEpoch()).isEqualTo(4);
        assertThat(mapper.find(first.view().id(), 9)).isNull();
        assertThat(mapper.countPendingVectorProjections(9, "test-model")).isZero();
        assertThat(jdbc.queryForList("SELECT state FROM agent_memory_projection", String.class)).containsOnly("DELETING");
        assertThat(tools.delete(9, runId, 4, first.view().id(), 2, "忘掉原来的回答习惯").status())
                .isEqualTo("ALREADY_DELETED");
        assertThatThrownBy(() -> tools.add(9, runId, 4, "PREFERENCE", "回答时先给结论", "回答时先给结论"))
                .hasMessage("IDEMPOTENCY_CONFLICT");
    }

    @Test void runFenceLeaseSettingOwnerAndSourceAreEnforcedByRealSql() {
        jdbc.update("UPDATE agent_turn SET run_fence=7 WHERE id=21");
        assertCannotAdd();
        jdbc.update("UPDATE agent_turn SET run_fence=6,lease_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=21");
        assertCannotAdd();
        jdbc.update("UPDATE agent_turn SET lease_until=DATE_ADD(NOW(6),INTERVAL 10 MINUTE) WHERE id=21");
        jdbc.update("UPDATE agent_run_guard SET active_run_type='TEMPORARY' WHERE user_id=9");
        assertCannotAdd();
        jdbc.update("UPDATE agent_run_guard SET active_run_type='PERSISTENT' WHERE user_id=9");
        jdbc.update("UPDATE agent_memory_setting SET enabled=0 WHERE user_id=9");
        assertCannotAdd();
        jdbc.update("UPDATE agent_memory_setting SET enabled=1 WHERE user_id=9");
        jdbc.update("UPDATE sys_user SET account_state='CLOSED' WHERE id=9");
        assertCannotAdd();
        jdbc.update("UPDATE sys_user SET account_state='ACTIVE' WHERE id=9");
        assertThatThrownBy(() -> tools.add(9, runId, 1, "PREFERENCE", "喜欢绿色衣服", "喜欢绿色衣服"))
                .hasMessage("VALIDATION_FAILED");
        long foreign = management.create(10, "PREFERENCE", "喜欢绿色衣服", null).id();
        assertThatThrownBy(() -> tools.delete(9, runId, 1, foreign, 1, "忘掉原来的回答习惯"))
                .hasMessage("RESOURCE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_item WHERE user_id=9", Integer.class)).isZero();
        assertThat(mapper.memoryEpoch(9)).isEqualTo(1);
    }

    @Test void sourceWriteFailureRollsBackSharedManagementTransactionAndEpoch() {
        AgentMemoryMapper failingSource = mock(AgentMemoryMapper.class, delegatesTo(mapper));
        doThrow(new IllegalStateException("Injected source insert failure")).when(failingSource)
                .insertSource(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        var failingTools = new AgentMemoryToolService(failingSource, management, transactions, new AgentMemorySafetyPolicy());
        assertThatThrownBy(() -> failingTools.add(9, runId, 1, "PREFERENCE", "回答时先给结论", "回答时先给结论"))
                .hasMessage("Injected source insert failure");
        assertThat(mapper.memoryEpoch(9)).isEqualTo(1);
        for (String table : java.util.List.of("agent_memory_item", "agent_memory_version", "agent_memory_source", "agent_memory_projection"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=9", Integer.class)).isZero();
    }

    @Test void externalCommitWhileToolWaitsOnConversationCancelsItsStaleEpoch() throws Exception {
        var first = tools.add(9, runId, 1, "PREFERENCE", "回答时先给结论", "回答时先给结论");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var external = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                mapper.lockConversationForMemory(9);
                management.edit(9, first.view().id(), "人工修改回答习惯", 1);
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var stale = executor.submit(() -> tools.update(9, runId, 2, first.view().id(), 1,
                    "回答时分步骤解释", "回答时分步骤解释"));
            release.countDown();
            external.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> stale.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
        }
        assertThat(mapper.memoryEpoch(9)).isEqualTo(3);
        assertThat(mapper.find(first.view().id(), 9).content()).isEqualTo("人工修改回答习惯");
    }

    @Test void projectionWorkAndPendingCountRespectCurrentVersionOwnerSettingExpiryAndModel() {
        long active = management.create(9, "PREFERENCE", "偏好简洁表达", null).id();
        long paused = management.create(9, "PREFERENCE", "偏好分段表达", null).id();
        long expired = management.create(9, "PREFERENCE", "偏好详细表达", null).id();
        long sensitive = management.create(9, "PREFERENCE", "偏好清晰表达", null).id();
        long correctModel = management.create(9, "PREFERENCE", "偏好举例表达", null).id();
        long other = management.create(10, "PREFERENCE", "另一个用户偏好", null).id();
        management.updateState(9, paused, true, 1);
        jdbc.update("UPDATE agent_memory_item SET expires_at=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?", expired);
        jdbc.update("UPDATE agent_memory_item SET sensitivity='SENSITIVE' WHERE id=?", sensitive);
        jdbc.update("UPDATE agent_memory_projection SET state='PROJECTED',embedding_model='model-current' WHERE memory_version_id=?",
                mapper.currentVersionId(correctModel, 9));
        assertThat(mapper.countPendingVectorProjections(9, "model-current")).isEqualTo(1);
        assertThat(mapper.countPendingVectorProjections(9, "model-new")).isEqualTo(2);
        assertThat(mapper.listPendingVectorProjections("model-current", 0, 20)).extracting(row -> row.memoryId())
                .containsExactly(active, other);
        long cursor = mapper.currentVersionId(active, 9);
        assertThat(mapper.listPendingVectorProjections("model-current", cursor, 20)).extracting(row -> row.memoryId())
                .containsExactly(other);
        jdbc.update("UPDATE agent_memory_setting SET enabled=0 WHERE user_id=10");
        assertThat(mapper.countPendingVectorProjections(10, "model-current")).isZero();
        assertThat(mapper.listPendingVectorProjections("model-current", cursor, 20)).isEmpty();
        management.edit(9, active, "改为精炼表达", 1);
        assertThat(mapper.listPendingVectorProjections("model-current", 0, 20)).singleElement()
                .satisfies(row -> assertThat(row.content()).isEqualTo("改为精炼表达"));
    }

    private void assertCannotAdd() {
        assertThatThrownBy(() -> tools.add(9, runId, 1, "PREFERENCE", "回答时先给结论", "回答时先给结论"))
                .isInstanceOf(CancellationException.class);
    }

}
