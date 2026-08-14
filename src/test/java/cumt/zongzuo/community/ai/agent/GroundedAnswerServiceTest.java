package cumt.zongzuo.community.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.retrieval.ArticleRetrievalQuery;
import cumt.zongzuo.community.ai.agent.retrieval.ArticleRetrievalResult;
import cumt.zongzuo.community.ai.agent.retrieval.HybridArticleRetrievalService;
import cumt.zongzuo.community.ai.agent.retrieval.RankedArticleChunk;
import cumt.zongzuo.community.ai.agent.retrieval.ResolvedArticleChunk;
import cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit;
import cumt.zongzuo.community.ai.agent.history.AgentConversationHistorySearchService;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryRecallService;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryView;
import cumt.zongzuo.community.ai.agent.planner.AgentPlannerRound;
import cumt.zongzuo.community.ai.agent.planner.AgentReadOnlyPlanProvider;
import cumt.zongzuo.community.ai.agent.planner.AgentReadOnlyTool;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSearchGateway;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSearchResult;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSource;
import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatGateway;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.userprovider.UserAiChatRouter;
import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;
import cumt.zongzuo.community.ai.userprovider.UserAiRoutedResult;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroundedAnswerServiceTest {

    private final HybridArticleRetrievalService retrieval = mock(HybridArticleRetrievalService.class);
    private final AiChatGateway gateway = mock(AiChatGateway.class);
    private final AgentMemoryRecallService memories = mock(AgentMemoryRecallService.class);
    private final AgentConversationHistorySearchService history =
            mock(AgentConversationHistorySearchService.class);
    private final AgentWebSearchGateway webSearch = mock(AgentWebSearchGateway.class);
    private final AtomicInteger calls = new AtomicInteger();
    private final ResolvedArticleChunk source = new ResolvedArticleChunk(31L, 301L, 3001L, 0,
            "MySQL locks", List.of("Transactions"),
            "Use SELECT FOR UPDATE to serialize writers around the current row.",
            "a".repeat(64), "b".repeat(64));

    @Test
    void returnsOnlyValidatedBackendGeneratedCitations() {
        retrievalResult(List.of(source));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"Use a row lock around the writer transaction.[1]","citations":[
                  {"marker":1,"sourceId":"A301:R3001:C31",
                   "quote":"Use SELECT FOR UPDATE to serialize writers"}]}
                """, "stop", 120, 32, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answer(9L, "request-1", "How do I serialize writers?",
                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.answer()).contains("[1]");
        assertThat(answer.citations()).containsExactly(new AgentCitation(1,
                "A301:R3001:C31", 301L, 3001L, 31L, "MySQL locks",
                "Use SELECT FOR UPDATE to serialize writers", "/article/301"));
        assertThat(answer.fundingSource()).isEqualTo(UserAiFundingSource.USER);
        assertThat(answer.provider()).isEqualTo("test");
        assertThat(answer.model()).isEqualTo("deepseek-test");
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsAnInventedCitationEvenWhenTheProviderReturnsValidJson() {
        retrievalResult(List.of(source));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"Invented claim.[1]","citations":[
                  {"marker":1,"sourceId":"A999:R999:C999","quote":"Invented quote"}]}
                """, "stop", 100, 20, "test", "deepseek-test"));

        assertThatThrownBy(() -> service().answer(9L, "request-2", "question",
                Instant.parse("2026-08-12T00:00:30Z")))
                .isInstanceOf(InvalidAgentAnswerException.class);
    }

    @Test
    void answersWithClearlyMarkedModelKnowledgeWhenCommunityEvidenceIsUnavailable() {
        retrievalResult(List.of());
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"可以先从问题目标和约束开始分析。","citations":[]}
                """, "stop", 80, 20, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answer(9L, "request-3", "unknown",
                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.answer()).startsWith("【模型通用知识】");
        assertThat(answer.citations()).isEmpty();
        verify(gateway).generate(any());
    }

    @Test
    void disabledMemoryIsNeitherReadNorSentToTheModel() {
        retrievalResult(List.of());
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"【模型通用知识】当前没有可使用的个人记忆。","citations":[]}
                """, "stop", 80, 20, "test", "deepseek-test"));
        GroundedAgentAnswer answer = service(false).answer(9L, "request-memory-off", "你记得我吗",
                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.finishReason()).isEqualTo("stop");
        verify(memories, never()).recall(any(Long.class), any(), any(Integer.class));
        verify(gateway).generate(any());
    }

    @Test
    void answersFromOwnerMemoryAndOldConversationWithoutCommunityCitations() {
        retrievalResult(List.of());
        when(memories.recall(9L, "你记得我喜欢什么，以及我说过的重话吗？", 6))
                .thenReturn(List.of(new AgentMemoryView(71L, "PREFERENCE",
                        "我喜欢简洁的回答风格", 2L, "ACTIVE", null,
                        "CONVERSATION")));
        when(history.search(9L, "你记得我喜欢什么，以及我说过的重话吗？", 6))
                .thenReturn(List.of(new AgentConversationHistoryHit(81L, 801L, 9L, "USER",
                        "你是我用过最难用的助手",
                        LocalDateTime.parse("2026-01-02T03:04:05"))));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"你喜欢简洁回答；你曾说我是你用过最难用的助手。","citations":[]}
                """, "stop", 100, 20, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answer(9L, "request-personal",
                "你记得我喜欢什么，以及我说过的重话吗？",
                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.citations()).isEmpty();
        assertThat(answer.memoryUses()).extracting(AgentMemoryUse::memoryId)
                .containsExactly(71L);
        assertThat(answer.historyUses()).extracting(AgentHistoryUse::messageId)
                .containsExactly(81L);
        var command = org.mockito.ArgumentCaptor.forClass(
                cumt.zongzuo.community.ai.provider.AiChatCommand.class);
        verify(gateway).generate(command.capture());
        assertThat(command.getValue().messages().toString())
                .contains("我喜欢简洁的回答风格", "你是我用过最难用的助手");
    }

    @Test
    void temporaryAnswerUsesOnlySuppliedSessionContextAndNeverReadsPersistentPersonalData() {
        retrievalResult(List.of());
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"你在这次临时对话里说过喜欢红色。","citations":[]}
                """, "stop", 80, 20, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answerTemporary(9L, "temporary-request",
                "我刚才说喜欢什么？", List.of("USER\t我喜欢红色"),
                Instant.parse("2026-08-12T00:00:30Z"));

        verify(memories, never()).recall(any(Long.class), any(), any(Integer.class));
        verify(history, never()).search(any(Long.class), any(), any(Integer.class));
        var command = org.mockito.ArgumentCaptor.forClass(
                cumt.zongzuo.community.ai.provider.AiChatCommand.class);
        verify(gateway).generate(command.capture());
        assertThat(command.getValue().messages().toString()).contains("我喜欢红色");
        assertThat(answer.memoryUses()).isEmpty();
        assertThat(answer.historyUses()).isEmpty();
    }

    @Test
    void enabledWebSearchRunsEvenWithCommunitySourcesAndKeepsSourceCategoriesSeparate() {
        retrievalResult(List.of(source));
        when(webSearch.search(any(), any())).thenReturn(new AgentWebSearchResult(
                "网上资料补充：MySQL 官方建议保持事务简短。",
                List.of(new AgentWebSource(1, "MySQL 事务文档",
                        "https://dev.mysql.com/doc/refman/8.4/en/commit.html", "MySQL"))));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"【站内文章】可以用行锁串行化写入。[1]\\n\\n【联网搜索】同时应保持事务简短。[W1]","citations":[
                  {"marker":1,"sourceId":"A301:R3001:C31",
                   "quote":"Use SELECT FOR UPDATE to serialize writers"}]}
                """, "stop", 120, 32, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answer(9L, "request-web", "如何控制并发写入？",
                true, Instant.parse("2026-08-12T00:00:30Z"));

        verify(webSearch).search("如何控制并发写入？", Instant.parse("2026-08-12T00:00:30Z"));
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.webSources()).containsExactly(new AgentWebSource(1, "MySQL 事务文档",
                "https://dev.mysql.com/doc/refman/8.4/en/commit.html", "MySQL"));
        var command = org.mockito.ArgumentCaptor.forClass(
                cumt.zongzuo.community.ai.provider.AiChatCommand.class);
        verify(gateway).generate(command.capture());
        assertThat(command.getValue().messages().toString())
                .contains("网上资料补充", "dev.mysql.com", "Use SELECT FOR UPDATE");
    }

    @Test
    void disabledWebSearchNeverCallsTheExternalGateway() {
        retrievalResult(List.of());
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"【模型通用知识】这是不联网的回答。","citations":[]}
                """, "stop", 20, 8, "test", "deepseek-test"));

        service().answer(9L, "request-web-off", "不要联网", false,
                Instant.parse("2026-08-12T00:00:30Z"));

        verify(webSearch, never()).search(any(), any());
    }

    @Test
    void webOnlyAnswerKeepsItsWebLabelInsteadOfBeingRelabeledAsModelKnowledge() {
        retrievalResult(List.of());
        when(webSearch.search(any(), any())).thenReturn(new AgentWebSearchResult(
                "北京今天晴朗。[W1]", List.of(new AgentWebSource(1, "天气资料",
                "https://example.com/weather", "Example"))));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"【联网搜索】北京今天晴朗。[W1]","citations":[]}
                """, "stop", 30, 8, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answer(9L, "request-web-only", "北京天气",
                true, Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.answer()).startsWith("【联网搜索】");
        assertThat(answer.webSources()).extracting(AgentWebSource::index).containsExactly(1);
    }

    @Test
    void rejectsAnInventedWebMarker() {
        retrievalResult(List.of());
        when(webSearch.search(any(), any())).thenReturn(new AgentWebSearchResult(
                "只提供了一个联网来源。[W1]", List.of(new AgentWebSource(1, "来源一",
                "https://example.com/one", "Example"))));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"【联网搜索】这是一个伪造来源。[W9]","citations":[]}
                """, "stop", 30, 8, "test", "deepseek-test"));

        assertThatThrownBy(() -> service().answer(9L, "request-web-invented", "问题",
                true, Instant.parse("2026-08-12T00:00:30Z")))
                .isInstanceOf(InvalidAgentAnswerException.class);
    }

    @Test
    void removesOnlyAuthorizedRedundantWebCitationsReturnedByTheModel() {
        retrievalResult(List.of());
        when(webSearch.search(any(), any())).thenReturn(new AgentWebSearchResult(
                "广州今天有雨。[W2]", List.of(new AgentWebSource(2, "广州天气",
                "https://example.com/guangzhou", "Example"))));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"【联网搜索】广州今天有雨。[W2]","citations":[
                  {"marker":"[W2]","sourceId":"webSearch","quote":"广州今天有雨"}]}
                """, "stop", 30, 8, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answer(9L, "request-web-redundant", "广州天气",
                true, Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.citations()).isEmpty();
        assertThat(answer.webSources()).extracting(AgentWebSource::index).containsExactly(2);
    }

    @Test
    void executesAtMostTwoPlannerRoundsAndFourUniqueReadOnlyTools() {
        AgentReadOnlyPlanProvider planner = mock(AgentReadOnlyPlanProvider.class);
        when(planner.maxRounds()).thenReturn(2);
        when(planner.maxToolCalls()).thenReturn(4);
        when(planner.plan(anyLong(), any(), any(), anyBoolean(), anyBoolean(), anyInt(),
                anySet(), any(), any())).thenReturn(
                new AgentPlannerRound(List.of(AgentReadOnlyTool.COMMUNITY_ARTICLES,
                        AgentReadOnlyTool.LONG_TERM_MEMORY), true),
                // 第二轮故意重复前两项，回答服务仍必须做独立的去重与预算校验。
                new AgentPlannerRound(List.of(AgentReadOnlyTool.COMMUNITY_ARTICLES,
                        AgentReadOnlyTool.LONG_TERM_MEMORY,
                        AgentReadOnlyTool.CONVERSATION_HISTORY,
                        AgentReadOnlyTool.WEB_SEARCH), true));
        retrievalResult(List.of());
        when(memories.recall(9L, "综合一下我的旧问题", 6)).thenReturn(List.of(
                new AgentMemoryView(72L, "PREFERENCE", "用户喜欢先看结论", 1L,
                        "ACTIVE", null, "CONVERSATION")));
        when(history.search(9L, "综合一下我的旧问题", 6)).thenReturn(List.of(
                new AgentConversationHistoryHit(82L, 802L, 9L, "USER", "旧问题内容",
                        LocalDateTime.parse("2026-01-02T03:04:05"))));
        when(webSearch.search(any(), any())).thenReturn(new AgentWebSearchResult(
                "外部补充资料。[W1]", List.of(new AgentWebSource(1, "外部资料",
                "https://example.com/source", "Example"))));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"【记忆与历史】你喜欢先看结论。\\n\\n【联网搜索】还有外部补充。[W1]",
                 "citations":[]}
                """, "stop", 80, 20, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service(true, planner).answer(9L, "request-planned",
                "综合一下我的旧问题", true,
                Instant.parse("2026-08-12T00:00:30Z"));

        verify(planner, org.mockito.Mockito.times(2)).plan(anyLong(), any(), any(),
                anyBoolean(), anyBoolean(), anyInt(), anySet(), any(), any());
        verify(retrieval).retrieve(any(ArticleRetrievalQuery.class));
        verify(memories).recall(9L, "综合一下我的旧问题", 6);
        verify(history).search(9L, "综合一下我的旧问题", 6);
        verify(webSearch).search("综合一下我的旧问题",
                Instant.parse("2026-08-12T00:00:30Z"));
        assertThat(answer.memoryUses()).hasSize(1);
        assertThat(answer.historyUses()).hasSize(1);
        assertThat(answer.webSources()).hasSize(1);
        assertThat(calls).hasValue(1);
    }

    private void retrievalResult(List<ResolvedArticleChunk> chunks) {
        when(retrieval.retrieve(any(ArticleRetrievalQuery.class))).thenReturn(new ArticleRetrievalResult(
                chunks.size(), chunks.size(), true, true, chunks,
                chunks.stream().map(chunk -> new RankedArticleChunk(chunk, .03, 1, 1)).toList()));
    }

    private GroundedAnswerService service() {
        return service(true);
    }

    private GroundedAnswerService service(boolean memoryEnabled) {
        return service(memoryEnabled, null);
    }

    private GroundedAnswerService service(boolean memoryEnabled,
                                          AgentReadOnlyPlanProvider planner) {
        UserAiChatRouter router = (userId, command) -> new UserAiRoutedResult(
                gateway.generate(command), UserAiFundingSource.USER);
        return new GroundedAnswerService(retrieval, new DirectExecutor(), router,
                new GroundedAnswerParser(new ObjectMapper()),
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                "deepseek-test", Duration.ofSeconds(30), memories, history, memoryEnabled,
                webSearch, planner);
    }

    private final class DirectExecutor implements AiCapabilityExecutor {
        @Override
        public <T> T execute(cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                             CheckedSupplier<T> operation) {
            assertThat(context.capability()).isEqualTo(AiCapability.AGENT);
            calls.incrementAndGet();
            try {
                return operation.get();
            } catch (Throwable error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public <A, T> T execute(cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                                AttemptObserver<A, T> observer, AttemptOperation<A, T> operation) {
            throw new UnsupportedOperationException();
        }
    }
}
