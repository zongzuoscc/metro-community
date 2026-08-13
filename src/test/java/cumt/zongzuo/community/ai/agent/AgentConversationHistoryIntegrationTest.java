package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.ai.agent.history.AgentConversationHistorySearchService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnAdmissionService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnCreateCommand;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnQueryService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {"metro.ai.enabled=true", "metro.ai.agent.enabled=true"})
class AgentConversationHistoryIntegrationTest extends IntegrationTestSupport {

    private static final long OWNER = 98_101L;
    private static final long OTHER = 98_102L;

    @Autowired AgentTurnAdmissionService admissions;
    @Autowired AgentTurnQueryService turnQueries;
    @Autowired AgentConversationHistorySearchService history;
    @Autowired StringRedisTemplate redis;
    @MockitoBean AgentTurnRunner runner;

    @BeforeEach
    void seed() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status,deleted)
                VALUES (?,?,'encoded',?,0,0,0),(?,?,'encoded',?,0,0,0)
                """, OWNER, "history-owner", "history-owner@example.test",
                OTHER, "history-other", "history-other@example.test");
    }

    @AfterEach
    void cleanup() {
        for (long userId : new long[]{OWNER, OTHER}) {
            redis.delete("agent:run:user:" + userId);
            jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='AGENT_TURN'");
            jdbcTemplate.update("UPDATE agent_conversation SET last_message_id=NULL WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_message WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_turn WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_episode WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_conversation WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_profile WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_run_guard WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id=?", userId);
        }
    }

    @Test
    void oldConversationCanBeFoundWithoutTurningItIntoProfileMemory() {
        admissions.admit(new AgentTurnCreateCommand(OWNER, UUID.randomUUID(),
                "今天天气不错，谢谢你的帮助。",
                "{}", "COMMUNITY_QA"));
        jdbcTemplate.update("UPDATE agent_run_guard SET active_run_id=NULL,active_run_type=NULL,lease_until=NULL WHERE user_id=?", OWNER);
        var harshTurn = admissions.admit(new AgentTurnCreateCommand(OWNER, UUID.randomUUID(),
                "你根本不懂我，这个助手真是太差劲了。",
                "{}", "COMMUNITY_QA"));
        jdbcTemplate.update("""
                INSERT INTO agent_message(user_id,turn_id,conversation_id,episode_id,role,state,
                  content,content_hash,created_at,completed_at)
                SELECT user_id,id,conversation_id,episode_id,'ASSISTANT','FINAL',
                  '你真是垃圾，这是助手的回复而不是用户说的话',REPEAT('a',64),NOW(6),NOW(6)
                FROM agent_turn WHERE id=? AND user_id=?
                """, harshTurn.turnId(), OWNER);
        jdbcTemplate.update("UPDATE agent_run_guard SET active_run_id=NULL,active_run_type=NULL,lease_until=NULL WHERE user_id=?", OWNER);
        admissions.admit(new AgentTurnCreateCommand(OTHER, UUID.randomUUID(),
                "你根本不懂我。", "{}", "COMMUNITY_QA"));

        var hits = history.search(OWNER, "我对你说过最重的话", 5);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.content()).contains("你根本不懂我", "太差劲");
            assertThat(hit.role()).isEqualTo("USER");
        });
        assertThat(hits).allMatch(hit -> hit.userId() == OWNER);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_memory_item WHERE user_id=?", Integer.class, OWNER)).isZero();
    }

    @Test
    void credentialsInConversationHistoryAreNeverReturnedForModelContext() {
        admissions.admit(new AgentTurnCreateCommand(OWNER, UUID.randomUUID(),
                "我的 API key 是 secret-history-123", "{}", "COMMUNITY_QA"));

        assertThat(history.search(OWNER, "我以前说过的 API key", 5)).isEmpty();
    }

    @Test
    void historyRailIsOwnerBoundAndUsesAnExclusiveTurnCursor() {
        var older = admissions.admit(new AgentTurnCreateCommand(OWNER, UUID.randomUUID(),
                "第一轮问题", "{}", "COMMUNITY_QA"));
        completeTurn(OWNER, older.turnId(), "第一轮完整回答");
        releaseGuard(OWNER);

        var newer = admissions.admit(new AgentTurnCreateCommand(OWNER, UUID.randomUUID(),
                "第二轮问题", "{}", "COMMUNITY_QA"));
        completeTurn(OWNER, newer.turnId(), "第二轮完整回答");
        releaseGuard(OWNER);

        var foreign = admissions.admit(new AgentTurnCreateCommand(OTHER, UUID.randomUUID(),
                "其他账号的问题", "{}", "COMMUNITY_QA"));
        completeTurn(OTHER, foreign.turnId(), "其他账号的回答");

        var firstPage = turnQueries.history(OWNER, null, 1);
        assertThat(firstPage.items()).singleElement().satisfies(item -> {
            assertThat(item.turnId()).isEqualTo(newer.turnId());
            assertThat(item.questionPreview()).isEqualTo("第二轮问题");
            assertThat(item.answerPreview()).isEqualTo("第二轮完整回答");
            assertThat(item.userMessage()).isEqualTo("第二轮问题");
            assertThat(item.finalMessage()).isEqualTo("第二轮完整回答");
        });
        assertThat(firstPage.nextBeforeTurnId()).isEqualTo(newer.turnId());

        var secondPage = turnQueries.history(OWNER, firstPage.nextBeforeTurnId(), 1);
        assertThat(secondPage.items()).singleElement()
                .extracting(item -> item.turnId()).isEqualTo(older.turnId());
        assertThat(secondPage.nextBeforeTurnId()).isNull();
        assertThat(firstPage.items()).noneMatch(item -> item.turnId() == foreign.turnId());
    }

    /** 把 admission 产生的 USER 消息补成一个可见的成功问答事实。 */
    private void completeTurn(long userId, long turnId, String answer) {
        jdbcTemplate.update("""
                INSERT INTO agent_message(user_id,turn_id,conversation_id,episode_id,role,state,
                  content,content_hash,created_at,completed_at)
                SELECT user_id,id,conversation_id,episode_id,'ASSISTANT','FINAL',?,REPEAT('b',64),NOW(6),NOW(6)
                FROM agent_turn WHERE id=? AND user_id=?
                """, answer, turnId, userId);
        jdbcTemplate.update("""
                UPDATE agent_turn SET state='SUCCEEDED',completed_at=NOW(6)
                WHERE id=? AND user_id=?
                """, turnId, userId);
    }

    /** 测试中不运行异步 runner，因此显式释放本用户的串行运行栅栏。 */
    private void releaseGuard(long userId) {
        jdbcTemplate.update("""
                UPDATE agent_run_guard
                SET active_run_id=NULL,active_run_type=NULL,lease_until=NULL
                WHERE user_id=?
                """, userId);
    }
}
