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
