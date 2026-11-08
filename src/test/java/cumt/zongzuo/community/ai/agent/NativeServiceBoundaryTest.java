package cumt.zongzuo.community.ai.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import cumt.zongzuo.community.ai.agent.context.*;
import cumt.zongzuo.community.ai.agent.history.AgentConversationHistorySearchService;
import cumt.zongzuo.community.ai.agent.memory.*;
import cumt.zongzuo.community.ai.agent.react.NativeAgentRuntime;
import cumt.zongzuo.community.ai.agent.retrieval.*;
import cumt.zongzuo.community.ai.agent.websearch.*;
import cumt.zongzuo.community.ai.provider.*;
import cumt.zongzuo.community.ai.userprovider.*;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;

/** 用真实 ReactAgent 执行业务工具，防止权限、用户上下文或取消边界被框架改造弱化。 */
class NativeServiceBoundaryTest {
    @Test
    void filterRemovesAnOldTemporaryTurnFromBothSubsequentAndFinalModelInputs() {
        var fixture = new Fixture();
        fixture.model = (user, prompt) -> {
            if (fixture.modelCalls.get() == 1) return structuredTool("FILTER",
                    "{\"fragmentIds\":[\"T0\",\"T1\"]}");
            assertThat(prompt.getContents()).contains("recent-question", "current-question")
                    .doesNotContain("outdated-private-preference", "old-answer");
            return done();
        };
        fixture.service(8).answerTemporary(9, "filter", "current-question",
                List.of("USER\t outdated-private-preference", "ASSISTANT\t old-answer",
                        "USER\t recent-question", "ASSISTANT\t recent-answer"), fixture.deadline);
        assertThat(fixture.answerPrompts.getFirst()).contains("recent-question", "current-question")
                .doesNotContain("outdated-private-preference", "old-answer");
    }

    @Test
    void summaryReplacesHistoryInBothRequestsAndPreservesTheLatestCompleteTurn() {
        var fixture = new Fixture();
        fixture.model = (user, prompt) -> {
            if (fixture.modelCalls.get() == 1) return structuredTool("SUMMARY",
                    "{\"fragmentIds\":[\"T0\",\"T1\"]}");
            assertThat(prompt.getContents()).contains("server-generated-short-summary", "recent-question")
                    .doesNotContain("old-detailed-question", "old-detailed-answer");
            return done();
        };
        fixture.service(8).answerTemporary(9, "summary", "current-question",
                List.of("USER\t old-detailed-question", "ASSISTANT\t old-detailed-answer",
                        "USER\t recent-question", "ASSISTANT\t recent-answer"), fixture.deadline);
        assertThat(fixture.answerPrompts.getFirst())
                .contains("server-generated-short-summary", "recent-question", "recent-answer")
                .doesNotContain("old-detailed-question", "old-detailed-answer");
    }

    @Test
    void filterCannotRemoveTheProtectedLatestCompleteTurn() {
        var fixture = new Fixture();
        fixture.model = (user, prompt) -> fixture.modelCalls.get() == 1
                ? structuredTool("FILTER", "{\"fragmentIds\":[\"T0\",\"T1\"]}") : done();
        fixture.service(8).answerTemporary(9, "protected", "current-question",
                List.of("USER\t recent-question", "ASSISTANT\t recent-answer"), fixture.deadline);
        assertThat(fixture.answerPrompts.getFirst()).contains("recent-question", "recent-answer", "FILTER=REJECTED");
    }

    @Test
    void filterCannotSplitAnOldQuestionAndAnswerPair() {
        var fixture = new Fixture();
        fixture.model = (user, prompt) -> fixture.modelCalls.get() == 1
                ? structuredTool("FILTER", "{\"fragmentIds\":[\"T0\"]}") : done();
        fixture.service(8).answerTemporary(9, "complete", "current-question",
                List.of("USER\t old-question", "ASSISTANT\t old-answer",
                        "USER\t recent-question", "ASSISTANT\t recent-answer"), fixture.deadline);
        assertThat(fixture.answerPrompts.getFirst()).contains("old-question", "old-answer", "FILTER=REJECTED");
    }

    @Test
    void oversizedRetrievedHistoryIsRemovedFromTheSharedViewBeforeTheNextRequest() {
        var fixture = new Fixture();
        when(fixture.history.search(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                new cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit(
                        100, 10, 9, "USER", "oversized-optional-history".repeat(20_000), LocalDateTime.now())));
        fixture.model = (user, prompt) -> {
            if (fixture.modelCalls.get() == 1) return tool("CONVERSATION_HISTORY", "old detail");
            assertThat(prompt.getContents()).contains("current-question").doesNotContain("oversized-optional-history");
            return done();
        };
        var answer = fixture.service(8).answer(9, "bounded", "current-question", fixture.deadline);
        assertThat(answer.historyUses()).isEmpty();
        assertThat(fixture.answerPrompts.getFirst()).doesNotContain("oversized-optional-history");
    }

    @Test
    void filteredPublicEvidenceDoesNotReturnThroughTheSecondaryWebQueryModel() {
        var fixture = new Fixture();
        var source = new ResolvedArticleChunk(31, 301, 3001, 0, "Public article", List.of(),
                "obsolete-public-fragment", "a".repeat(64), "b".repeat(64));
        doReturn(new ArticleRetrievalResult(1, 0, true, true, List.of(source), List.of())).when(fixture.retrieval).retrieve(any());
        fixture.model = (user, prompt) -> switch (fixture.modelCalls.get()) {
            case 1 -> structuredTool("FILTER", "{\"fragmentIds\":[\"A301:R3001:C31\"]}");
            case 2 -> tool("WEB_SEARCH", "public refined");
            default -> done();
        };
        fixture.service(8).answer(9, "filtered-public", "public question", true, fixture.deadline);
        assertThat(fixture.publicPrompts).hasSize(1);
        assertThat(fixture.publicPrompts.getFirst()).doesNotContain("obsolete-public-fragment");
    }

    @Test
    void previewTrimsWholeOldTurnsAndCannotReloadThemDuringFinalAssembly() {
        var fixture = new Fixture();
        var rows = new ArrayList<cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit>();
        for (int turn = 1; turn <= 24; turn++) {
            String question = turn <= 21 ? ("oldest-turn-" + turn + " ").repeat(100) : "newest-user-" + turn;
            String answer = turn <= 21 ? ("old-answer-" + turn + " ").repeat(100) : "newest-answer-" + turn;
            rows.add(new cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit(turn * 2L,
                    turn, 9, "USER", question, LocalDateTime.now()));
            rows.add(new cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit(turn * 2L + 1,
                    turn, 9, "ASSISTANT", answer, LocalDateTime.now()));
        }
        when(fixture.history.conversationPage(eq(9L), eq(Long.MAX_VALUE), anyInt())).thenReturn(
                new cumt.zongzuo.community.ai.agent.history.AgentConversationPage(rows, 1, false));
        fixture.model = (user, prompt) -> {
            assertThat(prompt.getContents()).contains("newest-user-22", "newest-answer-22", "newest-user-24", "newest-answer-24")
                    .doesNotContain("oldest-turn-1 ");
            return done();
        };
        fixture.service(8).answer(9, "preview", "current-question", fixture.deadline);
        assertThat(fixture.answerPrompts.getFirst()).contains("newest-user-22", "newest-answer-24", "\"contextReduced\":true")
                .doesNotContain("oldest-turn-1 ");
        verify(fixture.history, times(1)).conversationPage(anyLong(), anyLong(), anyInt());
    }

    private static ChatResponse structuredTool(String name, String json) {
        return NativeReActToolTest.response(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(name, "function", name, json))).build(), "tool_calls");
    }

    @Test
    void duplicateQueriesDoNotRepeatDownstreamAndNoProgressStopsTheAgent() {
        var fixture = new Fixture();
        fixture.model = (user, prompt) -> tool("COMMUNITY_ARTICLES", "question");
        fixture.service(8).answerTemporary(9, "duplicate", "question", List.of(), fixture.deadline);
        assertThat(fixture.queries).containsExactly("9:question");
        assertThat(fixture.modelCalls).hasValue(2);
    }

    @Test
    void toolBudgetPreventsAdditionalCallsEvenWhenProviderRequestsMultipleTools() {
        var fixture = new Fixture();
        fixture.model =
                (user, prompt) ->
                        NativeReActToolTest.response(
                                AssistantMessage.builder()
                                        .content("")
                                        .toolCalls(
                                                List.of(
                                                        new AssistantMessage.ToolCall(
                                                                "one",
                                                                "function",
                                                                "COMMUNITY_ARTICLES",
                                                                "{\"query\":\"first\"}"),
                                                        new AssistantMessage.ToolCall(
                                                                "two",
                                                                "function",
                                                                "COMMUNITY_ARTICLES",
                                                                "{\"query\":\"second\"}")))
                                        .build(),
                                "tool_calls");
        fixture.service(2).answerTemporary(9, "limit", "question", List.of(), fixture.deadline);
        assertThat(fixture.queries).containsExactly("9:question", "9:first");
        assertThat(fixture.modelCalls).hasValue(1);
    }

    @Test
    void independentConcurrentUsersCannotReadEachOthersToolContext() throws Exception {
        var fixture = new Fixture();
        var barrier = new CyclicBarrier(2);
        fixture.model =
                (user, prompt) -> {
                    if (prompt.getInstructions().stream()
                            .noneMatch(message -> message instanceof ToolResponseMessage)) {
                        try {
                            barrier.await(3, TimeUnit.SECONDS);
                        } catch (Exception error) {
                            throw new RuntimeException(error);
                        }
                        return tool("LONG_TERM_MEMORY", "owner preference");
                    }
                    String text = prompt.getInstructions().toString();
                    assertThat(text)
                            .contains("private-" + user)
                            .doesNotContain("private-" + (user == 9 ? 10 : 9));
                    return done();
                };
        when(fixture.memories.recall(anyLong(), any(), anyInt(), any()))
                .thenAnswer(
                        call -> {
                            long user = call.getArgument(0);
                            return List.of(
                                    new AgentMemoryView(
                                            user,
                                            "PREFERENCE",
                                            "private-" + user,
                                            1,
                                            "ACTIVE",
                                            null,
                                            "CONVERSATION"));
                        });
        var service = fixture.service(8);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> service.answer(9, "one", "question", fixture.deadline));
            var two = workers.submit(() -> service.answer(10, "two", "question", fixture.deadline));
            assertThat(one.get(5, TimeUnit.SECONDS).memoryUses())
                    .extracting(AgentMemoryUse::content)
                    .containsExactly("private-9");
            assertThat(two.get(5, TimeUnit.SECONDS).memoryUses())
                    .extracting(AgentMemoryUse::content)
                    .containsExactly("private-10");
        }
    }

    @Test
    void secondaryWebQueryCannotSeeMemoryOrThePrivateProposedQuery() {
        var fixture = new Fixture();
        fixture.model =
                (user, prompt) -> {
                    long results =
                            prompt.getInstructions().stream()
                                    .filter(message -> message instanceof ToolResponseMessage)
                                    .count();
                    return results == 0
                            ? tool("LONG_TERM_MEMORY", "preferences")
                            : results == 1 ? tool("WEB_SEARCH", "private-secret-proposed") : done();
                };
        when(fixture.memories.recall(anyLong(), any(), anyInt(), any()))
                .thenReturn(
                        List.of(
                                new AgentMemoryView(
                                        9,
                                        "PREFERENCE",
                                        "private-memory-secret",
                                        1,
                                        "ACTIVE",
                                        null,
                                        "CONVERSATION")));
        fixture.service(8).answer(9, "privacy", "public question", true, fixture.deadline);
        assertThat(fixture.webQueries).containsExactly("public question", "public refined");
        assertThat(fixture.publicPrompts).hasSize(1);
        assertThat(fixture.publicPrompts.getFirst())
                .contains("public question")
                .doesNotContain(
                        "private-memory-secret", "private-secret-proposed", "LONG_TERM_MEMORY");
    }

    @Test
    void memoryEpochChangeAfterInitialRetrievalPreventsAllPaidModelCalls() {
        var fixture = new Fixture();
        AtomicLong epoch = new AtomicLong();
        when(fixture.memories.epoch(9)).thenAnswer(call -> epoch.get());
        doAnswer(
                        call -> {
                            epoch.incrementAndGet();
                            return empty();
                        })
                .when(fixture.retrieval)
                .retrieve(any());
        assertThatThrownBy(
                        () -> fixture.service(8).answer(9, "epoch", "question", fixture.deadline))
                .isInstanceOf(CancellationException.class);
        assertThat(fixture.modelCalls).hasValue(0);
        assertThat(fixture.answerCalls).hasValue(0);
    }

    @Test
    void cancelledMemoryToolCannotInvokeAnotherModelOrFinalAnswer() {
        var fixture = new Fixture();
        fixture.model = (user, prompt) -> tool("LONG_TERM_MEMORY", "preferences");
        when(fixture.memories.recall(anyLong(), any(), anyInt(), any()))
                .thenThrow(
                        new IllegalStateException(
                                "wrapped", new CancellationException("cancelled")));
        assertThatThrownBy(
                        () -> fixture.service(8).answer(9, "cancel", "question", fixture.deadline))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(CancellationException.class);
        assertThat(fixture.modelCalls).hasValue(1);
        assertThat(fixture.answerCalls).hasValue(0);
    }

    @Test
    void unavailableArticleToolProducesSafeObservationAndDoesNotMasqueradeAsNoEvidence() {
        var fixture = new Fixture();
        doThrow(new IllegalStateException("secret SQL")).when(fixture.retrieval).retrieve(any());
        fixture.model =
                (user, prompt) -> {
                    assertThat(prompt.getInstructions().toString())
                            .contains("ERROR")
                            .doesNotContain("secret SQL");
                    return done();
                };
        fixture.service(8)
                .answerTemporary(9, "unavailable", "question", List.of(), fixture.deadline);
        assertThat(fixture.modelCalls).hasValue(1);
    }

    private static ChatResponse tool(String name, String query) {
        return NativeReActToolTest.response(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                name,
                                                "function",
                                                name,
                                                "{\"query\":\"" + query + "\"}")))
                        .build(),
                "tool_calls");
    }

    private static ChatResponse done() {
        return NativeReActToolTest.response(new AssistantMessage("finished"), "stop");
    }

    private static ArticleRetrievalResult empty() {
        return new ArticleRetrievalResult(0, 0, true, true, List.of(), List.of());
    }

    private static final class Fixture {
        final HybridArticleRetrievalService retrieval = mock(HybridArticleRetrievalService.class);
        final AgentMemoryRecallService memories = mock(AgentMemoryRecallService.class);
        final AgentConversationHistorySearchService history =
                mock(AgentConversationHistorySearchService.class);
        final AgentWebSearchGateway web = mock(AgentWebSearchGateway.class);
        final Clock clock = Clock.systemUTC();
        final Instant deadline = Instant.now().plusSeconds(20);
        final List<String> queries = new CopyOnWriteArrayList<>();
        final List<String> webQueries = new CopyOnWriteArrayList<>();
        final List<String> publicPrompts = new CopyOnWriteArrayList<>();
        final List<String> answerPrompts = new CopyOnWriteArrayList<>();
        final AtomicInteger modelCalls = new AtomicInteger();
        final AtomicInteger answerCalls = new AtomicInteger();
        BiFunction<Long, Prompt, ChatResponse> model = (user, prompt) -> done();

        Fixture() {
            when(retrieval.retrieve(any()))
                    .thenAnswer(
                            call -> {
                                ArticleRetrievalQuery query = call.getArgument(0);
                                queries.add(query.userId() + ":" + query.query());
                                return empty();
                            });
            when(web.search(any(), any()))
                    .thenAnswer(
                            call -> {
                                webQueries.add(call.getArgument(0));
                                return AgentWebSearchResult.empty();
                            });
        }

        GroundedAnswerService service(int tools) {
            var executor = new NativeReActToolTest.DirectExecutor();
            var budget = new AgentPromptBudget(new AgentContextProperties());
            UserAiChatRouter router =
                    new UserAiChatRouter() {
                        public UserAiRoutedResult generate(long user, AiChatCommand command) {
                            if (command.messages()
                                    .getFirst()
                                    .text()
                                    .startsWith("Generate a public web")) {
                                publicPrompts.add(command.messages().toString());
                                return result("{\"query\":\"public refined\"}");
                            }
                            if (command.messages().getFirst().text().startsWith("Summarize selected"))
                                return result("server-generated-short-summary");
                            answerPrompts.add(command.messages().toString());
                            answerCalls.incrementAndGet();
                            return result("{\"answer\":\"回答完成\",\"citations\":[]}");
                        }

                        public PreparedUserAiChat prepare(long user, String ignored) {
                            return new PreparedUserAiChat(
                                    "model",
                                    UserAiFundingSource.USER,
                                    command -> generate(user, command),
                                    () -> {},
                                    (command, observer) -> generate(user, command),
                                    prompt -> {
                                        modelCalls.incrementAndGet();
                                        return model.apply(user, prompt);
                                    });
                        }

                        private UserAiRoutedResult result(String text) {
                            return new UserAiRoutedResult(
                                    new AiChatResult(text, "stop", 3, 4, "test", "model"),
                                    UserAiFundingSource.USER);
                        }
                    };
            return new GroundedAnswerService(
                    retrieval,
                    executor,
                    router,
                    new GroundedAnswerParser(new ObjectMapper()),
                    clock,
                    "model",
                    Duration.ofSeconds(20),
                    memories,
                    history,
                    true,
                    web,
                    new NativeAgentRuntime(
                            executor,
                            new ObjectMapper(),
                            clock,
                            Duration.ofSeconds(5),
                            6,
                            tools,
                            budget,
                            100_000));
        }
    }
}
