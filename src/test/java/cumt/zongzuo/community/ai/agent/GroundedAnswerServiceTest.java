package cumt.zongzuo.community.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit;
import cumt.zongzuo.community.ai.agent.history.AgentConversationHistorySearchService;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryRecallService;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryView;
import cumt.zongzuo.community.ai.agent.react.*;
import cumt.zongzuo.community.ai.agent.retrieval.ArticleRetrievalQuery;
import cumt.zongzuo.community.ai.agent.retrieval.ArticleRetrievalResult;
import cumt.zongzuo.community.ai.agent.retrieval.HybridArticleRetrievalService;
import cumt.zongzuo.community.ai.agent.retrieval.RankedArticleChunk;
import cumt.zongzuo.community.ai.agent.retrieval.ResolvedArticleChunk;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class GroundedAnswerServiceTest {

    private final HybridArticleRetrievalService retrieval =
            mock(HybridArticleRetrievalService.class);
    private final AiChatGateway gateway = mock(AiChatGateway.class);
    private final AgentMemoryRecallService memories = mock(AgentMemoryRecallService.class);
    private final AgentConversationHistorySearchService history =
            mock(AgentConversationHistorySearchService.class);
    private final AgentWebSearchGateway webSearch = mock(AgentWebSearchGateway.class);
    private final AtomicInteger calls = new AtomicInteger();
    private final ResolvedArticleChunk source =
            new ResolvedArticleChunk(
                    31L,
                    301L,
                    3001L,
                    0,
                    "MySQL locks",
                    List.of("Transactions"),
                    "Use SELECT FOR UPDATE to serialize writers around the current row.",
                    "a".repeat(64),
                    "b".repeat(64));

    @Test
    void returnsOnlyValidatedBackendGeneratedCitations() {
        retrievalResult(List.of(source));
        when(gateway.generate(any()))
                .thenReturn(
                        new AiChatResult(
                                """
{"answer":"Use a row lock around the writer transaction.[1]","citations":[
  {"marker":1,"sourceId":"A301:R3001:C31",
   "quote":"Use SELECT FOR UPDATE to serialize writers"}]}
""",
                                "stop",
                                120,
                                32,
                                "test",
                                "deepseek-test"));

        GroundedAgentAnswer answer =
                service()
                        .answer(
                                9L,
                                "request-1",
                                "How do I serialize writers?",
                                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.answer()).contains("[1]");
        assertThat(answer.citations())
                .containsExactly(
                        new AgentCitation(
                                1,
                                "A301:R3001:C31",
                                301L,
                                3001L,
                                31L,
                                "MySQL locks",
                                "Use SELECT FOR UPDATE to serialize writers",
                                "/article/301"));
        assertThat(answer.fundingSource()).isEqualTo(UserAiFundingSource.USER);
        assertThat(answer.provider()).isEqualTo("test");
        assertThat(answer.model()).isEqualTo("deepseek-test");
        assertThat(calls.get()).isGreaterThan(1);
    }

    @Test
    void acceptsABracketedCitationMarkerFromAnOpenAiCompatibleModel() {
        retrievalResult(List.of(source));
        when(gateway.generate(any()))
                .thenReturn(
                        new AiChatResult(
                                """
{"answer":"Use a row lock around the writer transaction.[1]","citations":[
  {"marker":"[1]","sourceId":"A301:R3001:C31",
   "quote":"Use SELECT FOR UPDATE to serialize writers"}]}
""",
                                "stop",
                                120,
                                32,
                                "test",
                                "deepseek-test"));

        GroundedAgentAnswer answer =
                service()
                        .answer(
                                9L,
                                "request-text-marker",
                                "How do I serialize writers?",
                                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.citations()).extracting(AgentCitation::marker).containsExactly(1);
    }

    @Test
    void rejectsAnInventedCitationEvenWhenTheProviderReturnsValidJson() {
        retrievalResult(List.of(source));
        when(gateway.generate(any()))
                .thenReturn(
                        new AiChatResult(
                                """
{"answer":"Invented claim.[1]","citations":[
  {"marker":1,"sourceId":"A999:R999:C999","quote":"Invented quote"}]}
""",
                                "stop",
                                100,
                                20,
                                "test",
                                "deepseek-test"));

        assertThatThrownBy(
                        () ->
                                service()
                                        .answer(
                                                9L,
                                                "request-2",
                                                "question",
                                                Instant.parse("2026-08-12T00:00:30Z")))
                .isInstanceOf(InvalidAgentAnswerException.class);
    }

    @Test
    void answersWithClearlyMarkedModelKnowledgeWhenCommunityEvidenceIsUnavailable() {
        retrievalResult(List.of());
        when(gateway.generate(any()))
                .thenReturn(
                        new AiChatResult(
                                """
                                {"answer":"可以先从问题目标和约束开始分析。","citations":[]}
                                """,
                                "stop",
                                80,
                                20,
                                "test",
                                "deepseek-test"));

        GroundedAgentAnswer answer =
                service().answer(9L, "request-3", "unknown", Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.answer()).startsWith("【模型通用知识】");
        assertThat(answer.citations()).isEmpty();
        verify(gateway).generate(any());
    }

    @Test
    void disabledMemoryIsNeitherReadNorSentToTheModel() {
        retrievalResult(List.of());
        when(gateway.generate(any()))
                .thenReturn(
                        new AiChatResult(
                                """
                                {"answer":"【模型通用知识】当前没有可使用的个人记忆。","citations":[]}
                                """,
                                "stop",
                                80,
                                20,
                                "test",
                                "deepseek-test"));
        GroundedAgentAnswer answer =
                service(false)
                        .answer(
                                9L,
                                "request-memory-off",
                                "你记得我吗",
                                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.finishReason()).isEqualTo("stop");
        verify(memories, never()).recall(any(Long.class), any(), any(Integer.class), any());
        verify(gateway).generate(any());
    }

    @Test
    void answersFromOwnerMemoryAndOldConversationWithoutCommunityCitations() {
        retrievalResult(List.of());
        when(memories.recall(9L, "你记得我喜欢什么，以及我说过的重话吗？", 6, Instant.parse("2026-08-12T00:00:20Z")))
                .thenReturn(
                        List.of(
                                new AgentMemoryView(
                                        71L,
                                        "PREFERENCE",
                                        "我喜欢简洁的回答风格",
                                        2L,
                                        "ACTIVE",
                                        null,
                                        "CONVERSATION")));
        when(history.search(9L, "你记得我喜欢什么，以及我说过的重话吗？", 6))
                .thenReturn(
                        List.of(
                                new AgentConversationHistoryHit(
                                        81L,
                                        801L,
                                        9L,
                                        "USER",
                                        "你是我用过最难用的助手",
                                        LocalDateTime.parse("2026-01-02T03:04:05"))));
        when(gateway.generate(any()))
                .thenReturn(
                        new AiChatResult(
                                """
                                {"answer":"你喜欢简洁回答；你曾说我是你用过最难用的助手。","citations":[]}
                                """,
                                "stop",
                                100,
                                20,
                                "test",
                                "deepseek-test"));

        GroundedAgentAnswer answer =
                service()
                        .answer(
                                9L,
                                "request-personal",
                                "你记得我喜欢什么，以及我说过的重话吗？",
                                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.citations()).isEmpty();
        assertThat(answer.memoryUses()).extracting(AgentMemoryUse::memoryId).containsExactly(71L);
        assertThat(answer.historyUses())
                .extracting(AgentHistoryUse::messageId)
                .containsExactly(81L);
        var command =
                org.mockito.ArgumentCaptor.forClass(
                        cumt.zongzuo.community.ai.provider.AiChatCommand.class);
        verify(gateway).generate(command.capture());
        assertThat(command.getValue().messages().toString()).contains("我喜欢简洁的回答风格", "你是我用过最难用的助手");
    }

    @Test
    void temporaryAnswerUsesOnlySuppliedSessionContextAndNeverReadsPersistentPersonalData() {
        retrievalResult(List.of());
        when(gateway.generate(any()))
                .thenReturn(
                        new AiChatResult(
                                """
                                {"answer":"你在这次临时对话里说过喜欢红色。","citations":[]}
                                """,
                                "stop",
                                80,
                                20,
                                "test",
                                "deepseek-test"));

        GroundedAgentAnswer answer =
                service()
                        .answerTemporary(
                                9L,
                                "temporary-request",
                                "我刚才说喜欢什么？",
                                List.of("USER\t我喜欢红色"),
                                Instant.parse("2026-08-12T00:00:30Z"));

        verify(memories, never()).recall(any(Long.class), any(), any(Integer.class), any());
        verify(history, never()).search(any(Long.class), any(), any(Integer.class));
        verify(history, never()).recentConversation(anyLong(), anyInt());
        verify(history, never()).conversationPage(anyLong(), anyLong(), anyInt());
        verify(history, never()).recentSummaries(anyLong(), anyInt());
        var command =
                org.mockito.ArgumentCaptor.forClass(
                        cumt.zongzuo.community.ai.provider.AiChatCommand.class);
        verify(gateway).generate(command.capture());
        assertThat(command.getValue().messages().toString()).contains("我喜欢红色");
        assertThat(answer.memoryUses()).isEmpty();
        assertThat(answer.historyUses()).isEmpty();
    }

    @Test
    void enabledWebSearchRunsEvenWithCommunitySourcesAndKeepsSourceCategoriesSeparate() {
        retrievalResult(List.of(source));
        when(webSearch.search(any(), any()))
                .thenReturn(
                        new AgentWebSearchResult(
                                "网上资料补充：MySQL 官方建议保持事务简短。",
                                List.of(
                                        new AgentWebSource(
                                                1,
                                                "MySQL 事务文档",
                                                "https://dev.mysql.com/doc/refman/8.4/en/commit.html",
                                                "MySQL"))));
        when(gateway.generate(any()))
                .thenReturn(
                        new AiChatResult(
                                """
{"answer":"【站内文章】可以用行锁串行化写入。[1]\\n\\n【联网搜索】同时应保持事务简短。[W1]","citations":[
  {"marker":1,"sourceId":"A301:R3001:C31",
   "quote":"Use SELECT FOR UPDATE to serialize writers"}]}
""",
                                "stop",
                                120,
                                32,
                                "test",
                                "deepseek-test"));

        GroundedAgentAnswer answer =
                service()
                        .answer(
                                9L,
                                "request-web",
                                "如何控制并发写入？",
                                true,
                                Instant.parse("2026-08-12T00:00:30Z"));

        verify(webSearch).search("如何控制并发写入？", Instant.parse("2026-08-12T00:00:20Z"));
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.webSources())
                .containsExactly(
                        new AgentWebSource(
                                1,
                                "MySQL 事务文档",
                                "https://dev.mysql.com/doc/refman/8.4/en/commit.html",
                                "MySQL"));
        var command =
                org.mockito.ArgumentCaptor.forClass(
                        cumt.zongzuo.community.ai.provider.AiChatCommand.class);
        verify(gateway).generate(command.capture());
        assertThat(command.getValue().messages().toString())
                .contains("网上资料补充", "dev.mysql.com", "Use SELECT FOR UPDATE");
    }

    @Test
    void disabledWebSearchNeverCallsTheExternalGateway() {
        retrievalResult(List.of());
        when(gateway.generate(any()))
                .thenReturn(
                        new AiChatResult(
                                """
                                {"answer":"【模型通用知识】这是不联网的回答。","citations":[]}
                                """,
                                "stop",
                                20,
                                8,
                                "test",
                                "deepseek-test"));

        service()
                .answer(
                        9L,
                        "request-web-off",
                        "不要联网",
                        false,
                        Instant.parse("2026-08-12T00:00:30Z"));

        verify(webSearch, never()).search(any(), any());
    }

    @Test
    void webOnlyAnswerKeepsItsWebLabelInsteadOfBeingRelabeledAsModelKnowledge() {
        retrievalResult(List.of());
        when(webSearch.search(any(), any()))
                .thenReturn(
                        new AgentWebSearchResult(
                                "北京今天晴朗。[W1]",
                                List.of(
                                        new AgentWebSource(
                                                1,
                                                "天气资料",
                                                "https://example.com/weather",
                                                "Example"))));
        when(gateway.generate(any()))
                .thenReturn(
                        new AiChatResult(
                                """
                                {"answer":"【联网搜索】北京今天晴朗。[W1]","citations":[]}
                                """,
                                "stop",
                                30,
                                8,
                                "test",
                                "deepseek-test"));

        GroundedAgentAnswer answer =
                service()
                        .answer(
                                9L,
                                "request-web-only",
                                "北京天气",
                                true,
                                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.answer()).startsWith("【联网搜索】");
        assertThat(answer.webSources()).extracting(AgentWebSource::index).containsExactly(1);
    }

    @Test
    void rejectsAnInventedWebMarker() {
        retrievalResult(List.of());
        when(webSearch.search(any(), any()))
                .thenReturn(
                        new AgentWebSearchResult(
                                "只提供了一个联网来源。[W1]",
                                List.of(
                                        new AgentWebSource(
                                                1, "来源一", "https://example.com/one", "Example"))));
        when(gateway.generate(any()))
                .thenReturn(
                        new AiChatResult(
                                """
                                {"answer":"【联网搜索】这是一个伪造来源。[W9]","citations":[]}
                                """,
                                "stop",
                                30,
                                8,
                                "test",
                                "deepseek-test"));

        assertThatThrownBy(
                        () ->
                                service()
                                        .answer(
                                                9L,
                                                "request-web-invented",
                                                "问题",
                                                true,
                                                Instant.parse("2026-08-12T00:00:30Z")))
                .isInstanceOf(InvalidAgentAnswerException.class);
    }

    @Test
    void removesOnlyAuthorizedRedundantWebCitationsReturnedByTheModel() {
        retrievalResult(List.of());
        when(webSearch.search(any(), any()))
                .thenReturn(
                        new AgentWebSearchResult(
                                "广州今天有雨。[W1]",
                                List.of(
                                        new AgentWebSource(
                                                1,
                                                "广州天气",
                                                "https://example.com/guangzhou",
                                                "Example"))));
        when(gateway.generate(any()))
                .thenReturn(
                        new AiChatResult(
                                """
                                {"answer":"【联网搜索】广州今天有雨。[W1]","citations":[
                                  {"marker":"[W1]","sourceId":"webSearch","quote":"广州今天有雨"}]}
                                """,
                                "stop",
                                30,
                                8,
                                "test",
                                "deepseek-test"));

        GroundedAgentAnswer answer =
                service()
                        .answer(
                                9L,
                                "request-web-redundant",
                                "广州天气",
                                true,
                                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.citations()).isEmpty();
        assertThat(answer.webSources()).extracting(AgentWebSource::index).containsExactly(1);
    }

    private void simpleAnswer() {
        when(gateway.generate(any()))
                .thenReturn(
                        new AiChatResult(
                                "{\"answer\":\"整理好了\",\"citations\":[]}",
                                "stop",
                                80,
                                20,
                                "test",
                                "deepseek-test"));
    }

    private void retrievalResult(List<ResolvedArticleChunk> chunks) {
        when(retrieval.retrieve(any(ArticleRetrievalQuery.class)))
                .thenReturn(
                        new ArticleRetrievalResult(
                                chunks.size(),
                                chunks.size(),
                                true,
                                true,
                                chunks,
                                chunks.stream()
                                        .map(chunk -> new RankedArticleChunk(chunk, .03, 1, 1))
                                        .toList()));
    }

    /** 验证生产回答链路真的向前翻页，而不是仅让独立组装器具备翻页能力。 */
    @Test
    void sendsMoreThanTwentyFourTurnsThroughTheActualAnswerPipeline() {
        retrievalResult(List.of());
        var newest = new java.util.ArrayList<AgentConversationHistoryHit>();
        var oldest = new java.util.ArrayList<AgentConversationHistoryHit>();
        for (int turn = 1; turn <= 36; turn++) {
            var target = turn <= 12 ? oldest : newest;
            target.add(
                    new AgentConversationHistoryHit(
                            turn * 2,
                            turn,
                            9,
                            "USER",
                            "问题" + turn,
                            LocalDateTime.parse("2026-08-12T00:00:00")));
            target.add(
                    new AgentConversationHistoryHit(
                            turn * 2 + 1,
                            turn,
                            9,
                            "ASSISTANT",
                            "回答" + turn,
                            LocalDateTime.parse("2026-08-12T00:00:01")));
        }
        when(history.conversationPage(9, Long.MAX_VALUE, 24))
                .thenReturn(
                        new cumt.zongzuo.community.ai.agent.history.AgentConversationPage(
                                newest, 13, false));
        when(history.conversationPage(9, 13, 24))
                .thenReturn(
                        new cumt.zongzuo.community.ai.agent.history.AgentConversationPage(
                                oldest, 1, true));
        when(gateway.generate(any()))
                .thenAnswer(
                        invocation -> {
                            var command =
                                    invocation.getArgument(
                                            0,
                                            cumt.zongzuo.community.ai.provider.AiChatCommand.class);
                            String json = command.messages().getLast().text().split("\\n", 2)[1];
                            var messages =
                                    new ObjectMapper().readTree(json).path("recentConversation");
                            assertThat(messages).hasSize(72);
                            assertThat(messages.get(0).path("content").asText()).isEqualTo("问题1");
                            assertThat(messages.get(71).path("content").asText()).isEqualTo("回答36");
                            return new AiChatResult(
                                    "{\"answer\":\"根据之前的讨论继续\",\"citations\":[]}",
                                    "stop",
                                    5000,
                                    100,
                                    "test",
                                    "deepseek-test");
                        });
        assertThat(
                        service()
                                .answer(
                                        9,
                                        "paged-answer",
                                        "继续",
                                        Instant.parse("2026-08-12T00:00:30Z"))
                                .historyUses())
                .hasSize(72);
    }

    /** Planner 即使只选文章，第三个/继续等追问仍必须看到上一轮原始问答。 */
    @Test
    void followUpAlwaysCarriesRecentTurnsWithoutAHistoryToolCall() {
        retrievalResult(List.of());
        when(webSearch.search(any(), any())).thenReturn(AgentWebSearchResult.empty());
        NativeAgentRuntime planner = null;
        when(history.conversationPage(9L, Long.MAX_VALUE, 24))
                .thenReturn(
                        new cumt.zongzuo.community.ai.agent.history.AgentConversationPage(
                                List.of(
                                        new AgentConversationHistoryHit(
                                                10,
                                                1,
                                                9,
                                                "USER",
                                                "给三个事务优化方案",
                                                LocalDateTime.parse("2026-08-12T00:00:00")),
                                        new AgentConversationHistoryHit(
                                                11,
                                                1,
                                                9,
                                                "ASSISTANT",
                                                "第一缩短事务；第二加索引；第三异步化",
                                                LocalDateTime.parse("2026-08-12T00:00:01"))),
                                1,
                                true));
        when(gateway.generate(any()))
                .thenAnswer(
                        invocation -> {
                            var command =
                                    invocation.getArgument(
                                            0,
                                            cumt.zongzuo.community.ai.provider.AiChatCommand.class);
                            assertThat(command.messages().toString())
                                    .contains("recentConversation", "给三个事务优化方案", "第三异步化", "把第三个展开");
                            assertThat(command.maxOutputTokens()).isPositive();
                            return new AiChatResult(
                                    "{\"answer\":\"第三项是异步化\",\"citations\":[]}",
                                    "stop",
                                    200,
                                    30,
                                    "test",
                                    "deepseek-test");
                        });
        var answer =
                service(true, planner)
                        .answer(
                                9L,
                                "follow-up",
                                "把第三个展开",
                                true,
                                Instant.parse("2026-08-12T00:00:30Z"));
        assertThat(answer.answer()).contains("第三项是异步化");
        assertThat(answer.historyUses())
                .extracting(AgentHistoryUse::messageId)
                .containsExactly(10L, 11L);
        var query = org.mockito.ArgumentCaptor.forClass(ArticleRetrievalQuery.class);
        verify(retrieval).retrieve(query.capture());
        assertThat(query.getValue().query()).contains("给三个事务优化方案", "把第三个展开");
        // 检索只使用前 20 秒，为最终回答保留 10 秒，而不是耗尽整个请求。
        verify(webSearch).search("把第三个展开", Instant.parse("2026-08-12T00:00:20Z"));
    }

    /** 检索结果再多也不能无限扩大最终请求；裁掉的文章不得继续充当引用依据。 */
    @Test
    void oversizedEvidenceIsRemovedBeforeSendingThePrompt() {
        var huge =
                new ResolvedArticleChunk(
                        32L,
                        302L,
                        3002L,
                        0,
                        "巨大资料",
                        List.of(),
                        "低相关资料。".repeat(30_000),
                        "a".repeat(64),
                        "b".repeat(64));
        retrievalResult(List.of(huge));
        when(gateway.generate(any()))
                .thenAnswer(
                        invocation -> {
                            var command =
                                    invocation.getArgument(
                                            0,
                                            cumt.zongzuo.community.ai.provider.AiChatCommand.class);
                            assertThat(
                                            command.messages().stream()
                                                    .mapToInt(m -> m.text().length())
                                                    .sum())
                                    .isLessThan(100_000);
                            assertThat(command.messages().toString())
                                    .contains("解释事务")
                                    .doesNotContain("巨大资料");
                            return new AiChatResult(
                                    "{\"answer\":\"事务是一组原子操作\",\"citations\":[]}",
                                    "stop",
                                    100,
                                    20,
                                    "test",
                                    "deepseek-test");
                        });
        assertThat(
                        service()
                                .answer(
                                        9L,
                                        "budget-test",
                                        "解释事务",
                                        Instant.parse("2026-08-12T00:00:30Z"))
                                .citations())
                .isEmpty();
    }

    /** 摘要是可选资料，数据库超时不能使已经取得原文和文章证据的整轮回答失败。 */
    @Test
    void summaryTimeoutKeepsRecentDialogueAndArticleEvidence() {
        retrievalResult(List.of(source));
        NativeAgentRuntime planner = null;
        when(history.conversationPage(9L, Long.MAX_VALUE, 24))
                .thenReturn(
                        new cumt.zongzuo.community.ai.agent.history.AgentConversationPage(
                                List.of(
                                        new AgentConversationHistoryHit(
                                                10,
                                                1,
                                                9,
                                                "USER",
                                                "解释行锁",
                                                LocalDateTime.parse("2026-08-12T00:00:00")),
                                        new AgentConversationHistoryHit(
                                                11,
                                                1,
                                                9,
                                                "ASSISTANT",
                                                "行锁保护当前记录",
                                                LocalDateTime.parse("2026-08-12T00:00:01"))),
                                1,
                                true));
        when(history.recentSummaries(9L, 3))
                .thenThrow(
                        new org.springframework.dao.QueryTimeoutException("summary unavailable"));
        when(gateway.generate(any()))
                .thenAnswer(
                        invocation -> {
                            var command =
                                    invocation.getArgument(
                                            0,
                                            cumt.zongzuo.community.ai.provider.AiChatCommand.class);
                            var data =
                                    new ObjectMapper()
                                            .readTree(
                                                    command.messages()
                                                            .getLast()
                                                            .text()
                                                            .split("\\n", 2)[1]);
                            assertThat(data.path("recentConversation")).hasSize(2);
                            assertThat(data.path("sources")).hasSize(1);
                            assertThat(data.path("episodeSummaries")).isEmpty();
                            return new AiChatResult(
                                    "{\"answer\":\"继续解释行锁\",\"citations\":[]}",
                                    "stop",
                                    200,
                                    30,
                                    "test",
                                    "deepseek-test");
                        });
        var result = new java.util.concurrent.atomic.AtomicReference<GroundedAgentAnswer>();
        org.assertj.core.api.Assertions.assertThatCode(
                        () ->
                                result.set(
                                        service(true, planner)
                                                .answer(
                                                        9L,
                                                        "summary-timeout",
                                                        "继续",
                                                        Instant.parse("2026-08-12T00:00:30Z"))))
                .doesNotThrowAnyException();
        assertThat(result.get().historyUses())
                .extracting(AgentHistoryUse::messageId)
                .containsExactly(10L, 11L);
    }

    private GroundedAnswerService service() {
        return service(true);
    }

    private GroundedAnswerService service(boolean memoryEnabled) {
        return service(memoryEnabled, null);
    }

    private GroundedAnswerService service(boolean memoryEnabled, NativeAgentRuntime planner) {
        return service(memoryEnabled, planner, null);
    }

    private GroundedAnswerService service(
            boolean memoryEnabled,
            NativeAgentRuntime planner,
            cumt.zongzuo.community.ai.agent.context.AgentCompactionGraph compaction) {
        UserAiChatRouter router =
                new UserAiChatRouter() {
                    @Override
                    public UserAiRoutedResult generate(
                            long userId, cumt.zongzuo.community.ai.provider.AiChatCommand command) {
                        return new UserAiRoutedResult(
                                gateway.generate(command), UserAiFundingSource.USER);
                    }

                    @Override
                    public cumt.zongzuo.community.ai.userprovider.PreparedUserAiChat prepare(
                            long userId, String model) {
                        // 与真实 BYOK 路由一致：预算所见的资金来源和实际请求来源必须相同。
                        return new cumt.zongzuo.community.ai.userprovider.PreparedUserAiChat(
                                model,
                                UserAiFundingSource.USER,
                                command -> generate(userId, command),
                                () -> {},
                                (command, observer) ->
                                        new UserAiRoutedResult(
                                                gateway.stream(command, observer),
                                                UserAiFundingSource.USER),
                                prompt -> {
                                    var toolCalls =
                                            new java.util.ArrayList<
                                                    org.springframework.ai.chat.messages
                                                            .AssistantMessage.ToolCall>();
                                    if (prompt.getInstructions().stream()
                                            .noneMatch(
                                                    message ->
                                                            message
                                                                    instanceof
                                                                    org.springframework.ai.chat
                                                                            .messages
                                                                            .ToolResponseMessage)) {
                                        var options =
                                                (org.springframework.ai.model.tool
                                                                .ToolCallingChatOptions)
                                                        prompt.getOptions();
                                        for (var tool : options.getToolCallbacks()) {
                                            String name = tool.getToolDefinition().name();
                                            if (name.equals("LONG_TERM_MEMORY")
                                                    || name.equals("CONVERSATION_HISTORY"))
                                                toolCalls.add(
                                                        new org.springframework.ai.chat.messages
                                                                .AssistantMessage.ToolCall(
                                                                name,
                                                                "function",
                                                                name,
                                                                queryJson(
                                                                        prompt.getInstructions()
                                                                                .getLast()
                                                                                .getText()
                                                                                .split(
                                                                                        "\\n"
                                                                                            + "Initial"
                                                                                            + " server",
                                                                                        2)[0]
                                                                                .replaceFirst(
                                                                                        "^Current"
                                                                                            + " question:"
                                                                                            + " ",
                                                                                        ""))));
                                        }
                                    }
                                    var message =
                                            org.springframework.ai.chat.messages.AssistantMessage
                                                    .builder()
                                                    .content("检索结束")
                                                    .toolCalls(toolCalls)
                                                    .build();
                                    return new org.springframework.ai.chat.model.ChatResponse(
                                            List.of(
                                                    new org.springframework.ai.chat.model
                                                            .Generation(
                                                            message,
                                                            org.springframework.ai.chat.metadata
                                                                    .ChatGenerationMetadata
                                                                    .builder()
                                                                    .finishReason(
                                                                            toolCalls.isEmpty()
                                                                                    ? "stop"
                                                                                    : "tool_calls")
                                                                    .build())),
                                            org.springframework.ai.chat.metadata
                                                    .ChatResponseMetadata.builder()
                                                    .model(model)
                                                    .build());
                                });
                    }
                };
        return new GroundedAnswerService(
                retrieval,
                new DirectExecutor(),
                router,
                new GroundedAnswerParser(new ObjectMapper()),
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                "deepseek-test",
                Duration.ofSeconds(30),
                memories,
                history,
                memoryEnabled,
                webSearch,
                planner,
                new cumt.zongzuo.community.ai.agent.context.AgentPromptBudget(
                        new cumt.zongzuo.community.ai.agent.context.AgentContextProperties()),
                400_000,
                compaction);
    }

    @Test
    void streamsOnlyAnswerBeforeFullJsonExistsAndStillRejectsInvalidFinalCitation() {
        retrievalResult(List.of(source));
        var pieces = new java.util.ArrayList<String>();
        when(gateway.stream(any(), any()))
                .thenAnswer(
                        call -> {
                            var observer =
                                    call.getArgument(
                                            1,
                                            cumt.zongzuo.community.ai.provider.AiStreamObserver
                                                    .class);
                            observer.onDelta("{\"answer\":\"提前出现的正文");
                            assertThat(String.join("", pieces)).isEqualTo("提前出现的正文");
                            observer.onDelta(
                                    "[1]\",\"citations\":[{\"marker\":1,\"sourceId\":\"invented\",\"quote\":\"secret\"}]}");
                            return new AiChatResult(
                                    "{\"answer\":\"提前出现的正文[1]\",\"citations\":[{\"marker\":1,\"sourceId\":\"invented\",\"quote\":\"secret\"}]}",
                                    "stop",
                                    10,
                                    10,
                                    "test",
                                    "deepseek-test");
                        });
        assertThatThrownBy(
                        () ->
                                service(false)
                                        .answerTemporary(
                                                9,
                                                "streaming",
                                                "问题",
                                                List.of(),
                                                false,
                                                Instant.parse("2026-08-12T00:00:30Z"),
                                                () -> true,
                                                pieces::add))
                .isInstanceOf(InvalidAgentAnswerException.class);
        assertThat(String.join("", pieces)).isEqualTo("提前出现的正文[1]");
        verify(gateway, never()).generate(any());
        org.mockito.Mockito.verifyNoInteractions(memories, history);
    }

    @Test
    void persistentAnswerUsesPreparedContextBeforeRetrievalAndGeneration() {
        var graph = mock(cumt.zongzuo.community.ai.agent.context.AgentCompactionGraph.class);
        var run = java.util.UUID.randomUUID();
        when(graph.prepare(anyLong(), any(), any(), any(), any()))
                .thenReturn(
                        new cumt.zongzuo.community.ai.agent.context.AgentCompactionGraph.Prepared(
                                cumt.zongzuo.community.ai.agent.history.AgentConversationPage
                                        .empty(),
                                List.of(
                                        new cumt.zongzuo.community.ai.agent.history
                                                .AgentEpisodeSummaryView(
                                                1,
                                                1,
                                                "用户正在学习数据库事务",
                                                LocalDateTime.parse("2026-08-12T00:00:00")))));
        retrievalResult(List.of());
        when(gateway.generate(any()))
                .thenAnswer(
                        call -> {
                            var command =
                                    call.getArgument(
                                            0,
                                            cumt.zongzuo.community.ai.provider.AiChatCommand.class);
                            assertThat(command.messages().toString()).contains("用户正在学习数据库事务");
                            return new AiChatResult(
                                    "{\"answer\":\"继续讲解事务\",\"citations\":[]}",
                                    "stop",
                                    100,
                                    20,
                                    "test",
                                    "deepseek-test");
                        });
        service(false, null, graph)
                .answerPersistent(9, run, "继续", false, Instant.parse("2026-08-12T00:00:30Z"));
        var order = org.mockito.Mockito.inOrder(graph, retrieval, gateway);
        order.verify(graph).prepare(anyLong(), any(), any(), any(), any());
        order.verify(retrieval).retrieve(any());
        order.verify(gateway).generate(any());
        verify(history, never()).conversationPage(anyLong(), anyLong(), anyInt());
        verify(history, never()).recentSummaries(anyLong(), anyInt());
    }

    @Test
    void compactionFailurePreventsRetrievalAndFinalModelCall() {
        var graph = mock(cumt.zongzuo.community.ai.agent.context.AgentCompactionGraph.class);
        when(graph.prepare(anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Memory index unavailable"));
        assertThatThrownBy(
                        () ->
                                service(true, null, graph)
                                        .answerPersistent(
                                                9,
                                                java.util.UUID.randomUUID(),
                                                "继续",
                                                false,
                                                Instant.parse("2026-08-12T00:00:30Z")))
                .hasMessageContaining("index unavailable");
        org.mockito.Mockito.verifyNoInteractions(retrieval, gateway, memories);
    }

    @Test
    void temporaryAnswerNeverEntersPersistentCompaction() {
        var graph = mock(cumt.zongzuo.community.ai.agent.context.AgentCompactionGraph.class);
        retrievalResult(List.of());
        when(gateway.generate(any()))
                .thenReturn(
                        new AiChatResult(
                                "{\"answer\":\"临时回答\",\"citations\":[]}",
                                "stop",
                                100,
                                20,
                                "test",
                                "deepseek-test"));
        service(true, null, graph)
                .answerTemporary(
                        9,
                        "temporary",
                        "问题",
                        List.of(),
                        false,
                        Instant.parse("2026-08-12T00:00:30Z"));
        org.mockito.Mockito.verifyNoInteractions(graph, memories, history);
    }

    @Test
    void memoryToolFailureIsNotSilentlyDowngradedToModelKnowledge() {
        NativeAgentRuntime planner = null;
        retrievalResult(List.of(source));
        when(memories.recall(anyLong(), any(), anyInt(), any()))
                .thenThrow(new IllegalStateException("Embedding unavailable"));
        assertThatThrownBy(
                        () ->
                                service(true, planner)
                                        .answer(
                                                9,
                                                "memory-failure",
                                                "偏好",
                                                false,
                                                Instant.parse("2026-08-12T00:00:30Z")))
                .hasMessageContaining("Embedding unavailable");
        verify(gateway, never()).generate(any());
    }

    private static String queryJson(String question) {
        try {
            return new ObjectMapper().writeValueAsString(java.util.Map.of("query", question));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private final class DirectExecutor implements AiCapabilityExecutor {
        @Override
        public <T> T execute(
                cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
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
        public <A, T> T execute(
                cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                AttemptObserver<A, T> observer,
                AttemptOperation<A, T> operation) {
            throw new UnsupportedOperationException();
        }
    }
}
