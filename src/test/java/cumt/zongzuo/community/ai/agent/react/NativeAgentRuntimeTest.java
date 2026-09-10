package cumt.zongzuo.community.ai.agent.react;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import cumt.zongzuo.community.ai.agent.context.AgentContextProperties;
import cumt.zongzuo.community.ai.agent.context.AgentPromptBudget;
import cumt.zongzuo.community.ai.agent.planner.AgentReadOnlyTool;
import cumt.zongzuo.community.ai.provider.*;
import cumt.zongzuo.community.ai.runtime.*;
import cumt.zongzuo.community.ai.userprovider.*;

import io.github.resilience4j.core.functions.CheckedSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

class NativeAgentRuntimeTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC);
    private final Instant deadline = clock.instant().plusSeconds(30);
    private final List<AgentToolCall> executed = new ArrayList<>();
    private final List<org.springframework.ai.chat.prompt.Prompt> prompts = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void oversizedModelContentStillRespectsTheDecisionResponseBoundary() {
        var route =
                route(
                        prompt ->
                                response(
                                        new AssistantMessage("x".repeat(32_001)), "stop", "model"));
        assertThatThrownBy(() -> run(route, 6, () -> false))
                .isInstanceOf(ReActDecisionException.class)
                .hasMessage("REACT_RESPONSE_TOO_LARGE");
    }

    @Test
    void frameworkCannotSwallowExecutionCancellationOrAdmissionFailure() {
        var failure = new AiExecutionException(AiExecutionErrorReason.CANCELLED, "cancelled");
        assertThatThrownBy(
                        () ->
                                run(
                                        route(
                                                prompt -> {
                                                    throw failure;
                                                }),
                                        6,
                                        () -> false))
                .isSameAs(failure);
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({
        "LONG_TERM_MEMORY,REACT_FORBIDDEN_TOOL",
        "CONVERSATION_HISTORY,REACT_FORBIDDEN_TOOL",
        "WEB_SEARCH,REACT_FORBIDDEN_TOOL",
        "WRITE_DATABASE,REACT_UNKNOWN_TOOL"
    })
    void nativeToolRequestsCannotExpandServerSideAuthorization(String tool, String code) {
        var route = route(prompt -> tool(tool, "{\"query\":\"q\"}"));
        assertThatThrownBy(() -> run(route, 6, () -> false))
                .isInstanceOf(ReActDecisionException.class)
                .hasMessage(code);
        assertThat(executed).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"query\":\"q\",\"userId\":7}",
                "{\"query\":\"q\",\"url\":\"https://private\"}",
                "{\"query\":\"q\",\"query\":\"other\"}",
                "[]",
                "{\"query\":null}",
                "{\"query\":\"\"}",
                "{\"query\":\"q\"} trailing"
            })
    void malformedNativeArgumentsNeverReachAReadOnlyTool(String json) {
        var route = route(prompt -> tool("COMMUNITY_ARTICLES", json));
        assertThatThrownBy(() -> run(route, 6, () -> false))
                .isInstanceOf(ReActDecisionException.class);
        assertThat(executed).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "length,model,REACT_RESPONSE_TRUNCATED",
        "content_filter,model,REACT_RESPONSE_INCOMPLETE",
        "stop,wrong-model,REACT_ROUTE_MISMATCH"
    })
    void rejectsIncompleteOrWrongFrozenRouteResponses(String finish, String model, String code) {
        var route = route(prompt -> response(new AssistantMessage("response"), finish, model));
        assertThatThrownBy(() -> run(route, 6, () -> false))
                .isInstanceOf(ReActDecisionException.class)
                .hasMessage(code);
        assertThat(executed).isEmpty();
    }

    @Test
    void nativeModelLimitStopsRepeatedToolRequestsWithoutAnotherPaidCall() {
        var route = route(prompt -> tool("COMMUNITY_ARTICLES", "{\"query\":\"q\"}"));
        run(route, 2, () -> false);
        assertThat(calls).hasValue(2);
        assertThat(executed).hasSize(2);
        assertThat(prompts.get(1).getInstructions())
                .anyMatch(message -> message instanceof ToolResponseMessage);
    }

    @Test
    void fatalToolCancellationDoesNotBecomeAnObservationAndAnotherPaidCall() {
        var route = route(prompt -> tool("COMMUNITY_ARTICLES", "{\"query\":\"q\"}"));
        assertThatThrownBy(
                        () ->
                                runtime(6)
                                        .run(
                                                9,
                                                "fatal",
                                                "question",
                                                List.of(),
                                                List.of(),
                                                Set.of(AgentReadOnlyTool.COMMUNITY_ARTICLES),
                                                route,
                                                deadline,
                                                () -> false,
                                                call -> {
                                                    throw new java.util.concurrent
                                                            .CancellationException("cancelled");
                                                }))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void publicQueryPromptCannotReceivePrivateObservations() {
        var route =
                new PreparedUserAiChat(
                        "model",
                        UserAiFundingSource.USER,
                        command -> {
                            throw new AssertionError("must not invoke");
                        });
        assertThatThrownBy(
                        () ->
                                runtime(6)
                                        .publicWebQuery(
                                                9,
                                                "public",
                                                "question",
                                                List.of(
                                                        new AgentToolObservation(
                                                                new AgentToolCall(
                                                                        AgentReadOnlyTool
                                                                                .LONG_TERM_MEMORY,
                                                                        "private"),
                                                                "OK",
                                                                "private secret")),
                                                route,
                                                deadline))
                .isInstanceOf(ReActDecisionException.class)
                .hasMessage("REACT_FORBIDDEN_TOOL");
    }

    @Test
    void publicQueryUsesOnlyItsExplicitQuestionAndPublicEvidenceAndStrictShape() {
        var route =
                new PreparedUserAiChat(
                        "model",
                        UserAiFundingSource.USER,
                        command -> {
                            assertThat(command.messages().toString())
                                    .contains("question", "public evidence")
                                    .doesNotContain("private-memory");
                            return new UserAiRoutedResult(
                                    new AiChatResult(
                                            "{\"query\":\"public refined\"}",
                                            "stop",
                                            2,
                                            3,
                                            "test",
                                            "model"),
                                    UserAiFundingSource.USER);
                        });
        var query =
                runtime(6)
                        .publicWebQuery(
                                9,
                                "public",
                                "question",
                                List.of(
                                        new AgentToolObservation(
                                                new AgentToolCall(
                                                        AgentReadOnlyTool.COMMUNITY_ARTICLES,
                                                        "question"),
                                                "OK",
                                                "public evidence")),
                                route,
                                deadline);
        assertThat(query).isEqualTo("public refined");
    }

    @Test
    void oversizedRequiredContextDoesNotInvokeTheProvider() {
        var route = route(prompt -> response(new AssistantMessage("done"), "stop", "model"));
        assertThatThrownBy(
                        () ->
                                new NativeAgentRuntime(
                                                new DirectExecutor(),
                                                new ObjectMapper(),
                                                clock,
                                                Duration.ofSeconds(5),
                                                6,
                                                8,
                                                new AgentPromptBudget(new AgentContextProperties()),
                                                10)
                                        .run(
                                                9,
                                                "large",
                                                "question".repeat(100),
                                                List.of(),
                                                List.of(),
                                                Set.of(AgentReadOnlyTool.COMMUNITY_ARTICLES),
                                                route,
                                                deadline,
                                                () -> false,
                                                call ->
                                                        new AgentToolObservation(
                                                                call, "EMPTY", "")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void noProgressBoundaryStopsBeforeCallingTheModel() {
        run(route(prompt -> tool("COMMUNITY_ARTICLES", "{\"query\":\"q\"}")), 6, () -> true);
        assertThat(calls).hasValue(0);
        assertThat(executed).isEmpty();
    }

    private void run(
            PreparedUserAiChat route, int rounds, java.util.function.BooleanSupplier stopped) {
        runtime(rounds)
                .run(
                        9,
                        "native",
                        "question",
                        List.of(),
                        List.of(),
                        Set.of(AgentReadOnlyTool.COMMUNITY_ARTICLES),
                        route,
                        deadline,
                        stopped,
                        call -> {
                            executed.add(call);
                            return new AgentToolObservation(call, "OK", "evidence");
                        });
    }

    private NativeAgentRuntime runtime(int rounds) {
        return new NativeAgentRuntime(
                new DirectExecutor(),
                new ObjectMapper(),
                clock,
                Duration.ofSeconds(5),
                rounds,
                8,
                new AgentPromptBudget(new AgentContextProperties()),
                100_000);
    }

    private PreparedUserAiChat route(ChatModel model) {
        return new PreparedUserAiChat(
                "model",
                UserAiFundingSource.USER,
                command -> {
                    throw new AssertionError("text protocol must not be used");
                },
                () -> {},
                (command, observer) -> {
                    throw new AssertionError();
                },
                prompt -> {
                    calls.incrementAndGet();
                    prompts.add(prompt);
                    return model.call(prompt);
                });
    }

    private ChatResponse tool(String tool, String query) {
        return response(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "call-" + calls.get(), "function", tool, query)))
                        .build(),
                "tool_calls",
                "model");
    }

    private ChatResponse response(AssistantMessage message, String finish, String model) {
        return new ChatResponse(
                List.of(
                        new Generation(
                                message,
                                ChatGenerationMetadata.builder().finishReason(finish).build())),
                ChatResponseMetadata.builder()
                        .model(model)
                        .usage(new DefaultUsage(3, 4, 7))
                        .build());
    }

    private static class DirectExecutor implements AiCapabilityExecutor {
        public <T> T execute(AiInvocationContext context, CheckedSupplier<T> operation) {
            try {
                return operation.get();
            } catch (RuntimeException error) {
                throw error;
            } catch (Throwable error) {
                throw new RuntimeException(error);
            }
        }

        public <A, T> T execute(
                AiInvocationContext context,
                AttemptObserver<A, T> observer,
                AttemptOperation<A, T> operation) {
            throw new UnsupportedOperationException();
        }
    }
}
