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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

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
            // 历史引用和检索快照都是消息、turn 的子记录，测试清理必须遵守真实外键顺序。
            jdbcTemplate.update("DELETE FROM agent_answer_citation WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM agent_retrieval_hit WHERE user_id=?", userId);
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

    @Test
    void historyReturnsDurableCommunityAndWebSourcesForEveryVisibleAnswer() {
        var turn = admissions.admit(new AgentTurnCreateCommand(OWNER, UUID.randomUUID(),
                "这次回答参考了哪些资料？", "{}", "COMMUNITY_QA"));
        completeTurn(OWNER, turn.turnId(), "站内结论。[1] 联网补充。[W1]");
        Long assistantMessageId = jdbcTemplate.queryForObject("""
                SELECT id FROM agent_message
                WHERE turn_id=? AND user_id=? AND role='ASSISTANT'
                """, Long.class, turn.turnId(), OWNER);
        jdbcTemplate.update("""
                INSERT INTO agent_answer_citation
                  (user_id,assistant_message_id,ordinal,article_id,revision_id,chunk_id,
                   title_snapshot,quote_snapshot,quote_hash,state,created_at)
                VALUES (?,?,?,?,?,?,?, ?,REPEAT('c',64),'ACTIVE',NOW(6))
                """, OWNER, assistantMessageId, 1, 42L, 420L, 4200L,
                "站内文章标题", "用于回答的站内原文");
        jdbcTemplate.update("""
                INSERT INTO agent_retrieval_hit
                  (user_id,turn_id,source_type,source_key,article_id,revision_id,chunk_id,
                   memory_id,bm25_score,dense_score,rrf_score,rank_no,excerpt_snapshot,
                   metadata_json,expires_at)
                VALUES (?,?,'WEB','web:1:test',NULL,NULL,NULL,NULL,NULL,NULL,1.0,1,?,
                        JSON_OBJECT('index',1,'url','https://example.com/news',
                                    'siteName','示例站'),DATE_ADD(NOW(6),INTERVAL 30 DAY))
                """, OWNER, turn.turnId(), "外部来源标题");
        jdbcTemplate.update("""
                INSERT INTO agent_retrieval_hit
                  (user_id,turn_id,source_type,source_key,article_id,revision_id,chunk_id,
                   memory_id,bm25_score,dense_score,rrf_score,rank_no,excerpt_snapshot,
                   metadata_json,expires_at)
                VALUES (?,?,'WEB','web:2:expired',NULL,NULL,NULL,NULL,NULL,NULL,1.0,2,?,
                        JSON_OBJECT('index',2,'url','https://expired.example/news',
                                    'siteName','过期站点'),DATE_SUB(NOW(6),INTERVAL 1 SECOND)),
                       (?,?,'WEB','web:3:unsafe',NULL,NULL,NULL,NULL,NULL,NULL,1.0,3,?,
                        JSON_OBJECT('index',3,'url','javascript:alert(1)',
                                    'siteName','不安全站点'),DATE_ADD(NOW(6),INTERVAL 30 DAY))
                """, OWNER, turn.turnId(), "已过期来源", OWNER, turn.turnId(), "不安全来源");

        var page = turnQueries.history(OWNER, null, 10);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.citations()).singleElement().satisfies(citation -> {
                assertThat(citation.marker()).isEqualTo(1);
                assertThat(citation.title()).isEqualTo("站内文章标题");
                assertThat(citation.url()).isEqualTo("/article/42");
            });
            assertThat(item.webSources()).singleElement().satisfies(source -> {
                assertThat(source.index()).isEqualTo(1);
                assertThat(source.title()).isEqualTo("外部来源标题");
                assertThat(source.url()).isEqualTo("https://example.com/news");
                assertThat(source.siteName()).isEqualTo("示例站");
            });
            assertThat(item.webSourcesExpired()).isTrue();
        });
    }

    @Test
    void resettingContextKeepsVisibleHistoryButStopsModelSearchAcrossEpisodes() {
        var oldTurn = admissions.admit(new AgentTurnCreateCommand(OWNER, UUID.randomUUID(),
                "请记住这段只属于旧上下文的蓝色风筝", "{}", "COMMUNITY_QA"));
        completeTurn(OWNER, oldTurn.turnId(), "我已经看到蓝色风筝。");
        releaseGuard(OWNER);
        assertThat(history.search(OWNER, "蓝色风筝", 5)).isNotEmpty();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(OWNER));
        var reset = restTemplate.exchange(url("/api/agent/turns/context/reset"),
                HttpMethod.POST, new HttpEntity<>(null, headers), String.class);

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(turnQueries.history(OWNER, null, 10).items()).singleElement()
                .extracting(item -> item.turnId()).isEqualTo(oldTurn.turnId());
        assertThat(history.search(OWNER, "蓝色风筝", 5)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_episode
                WHERE user_id=? AND state='ACTIVE'
                """, Integer.class, OWNER)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_episode
                WHERE user_id=? AND state='SEALED'
                """, Integer.class, OWNER)).isOne();
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
