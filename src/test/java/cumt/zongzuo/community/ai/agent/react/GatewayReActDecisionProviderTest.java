package cumt.zongzuo.community.ai.agent.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.context.AgentContextProperties;
import cumt.zongzuo.community.ai.agent.context.AgentPromptBudget;
import cumt.zongzuo.community.ai.agent.planner.AgentReadOnlyTool;
import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatCommand;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.ai.provider.AiPromptMessage;
import cumt.zongzuo.community.ai.provider.AiPromptRole;
import cumt.zongzuo.community.ai.provider.AiResponseMode;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import cumt.zongzuo.community.ai.userprovider.PreparedUserAiChat;
import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;
import cumt.zongzuo.community.ai.userprovider.UserAiRoutedResult;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayReActDecisionProviderTest {

    private static final Instant NOW = Instant.parse("2026-09-09T02:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(30);

    @Test
    void publicWebQuerySendsOnlyTheQuestionAndPublicObservationsThroughTheFrozenRoute() {
        RecordingExecutor executor = new RecordingExecutor();
        AtomicReference<AiChatCommand> sent = new AtomicReference<>();
        AtomicInteger invocations = new AtomicInteger();
        GatewayReActDecisionProvider provider = provider(executor, 6, 8, 100_000);
        List<AgentToolObservation> publicObservations = List.of(
                new AgentToolObservation(new AgentToolCall(
                        AgentReadOnlyTool.COMMUNITY_ARTICLES, "当前原始问题"),
                        "SUCCESS", "公开文章证据 #A1"),
                new AgentToolObservation(new AgentToolCall(
                        AgentReadOnlyTool.WEB_SEARCH, "当前原始问题"),
                        "SUCCESS", "已公开的网页摘要 #W1"));

        String query = provider.publicWebQuery(91L, "public-request", "当前原始问题",
                publicObservations, route("frozen-model", """
                        {"action":"CALL","tool":"WEB_SEARCH","query":"  SELECT FOR UPDATE 技术讨论  "}
                        """, sent, invocations), DEADLINE);

        assertThat(query).isEqualTo("SELECT FOR UPDATE 技术讨论");
        assertThat(invocations).hasValue(1);
        assertThat(sent.get().responseMode()).isEqualTo(AiResponseMode.JSON_OBJECT);
        assertThat(sent.get().maxOutputTokens()).isEqualTo(512);
        String serializedPrompt = sent.get().messages().stream()
                .map(AiPromptMessage::text).reduce("", String::concat);
        assertThat(serializedPrompt)
                .contains("当前原始问题", "公开文章证据 #A1", "已公开的网页摘要 #W1")
                .doesNotContain("recentContext", "persistentAllowed", "LONG_TERM_MEMORY",
                        "CONVERSATION_HISTORY", "public-request", "\"userId\"", "91");
        assertThat(executor.context.userId()).isEqualTo(91L);
        assertThat(executor.context.requestId()).isEqualTo("public-request:react:public-web-query");
        assertThat(executor.context.inputCharacters()).isEqualTo(sent.get().messages().stream()
                .mapToInt(message -> message.text().length()).sum());
        assertThat(executor.context.deadline()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void publicWebQueryDefaultImplementationFailsClosed() {
        AgentReactDecisionProvider replacement = new AgentReactDecisionProvider() {
            @Override
            public AgentReactDecision decide(long userId, String requestId, String question,
                                             List<AiPromptMessage> recentContext,
                                             boolean persistentAllowed, boolean webEnabled,
                                             int step, int remaining,
                                             List<AgentToolObservation> observations,
                                             PreparedUserAiChat route, Instant deadline) {
                return new AgentReactDecision(null);
            }

            @Override
            public int maxRounds() {
                return 1;
            }

            @Override
            public int maxToolCalls() {
                return 1;
            }
        };

        assertThatThrownBy(() -> replacement.publicWebQuery(9L, "request", "question",
                List.of(), route("model", "{\"action\":\"FINISH\"}"), DEADLINE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public web query");
    }

    @Test
    void publicWebQueryReturnsNullOnlyForTheExactFinishShape() {
        GatewayReActDecisionProvider provider = provider(new RecordingExecutor(), 6, 8, 100_000);

        assertThat(provider.publicWebQuery(9L, "finish", "question", List.of(),
                route("model", "{\"action\":\"FINISH\"}"), DEADLINE)).isNull();
        assertThatThrownBy(() -> provider.publicWebQuery(9L, "extra-user", "question", List.of(),
                route("model", "{\"action\":\"FINISH\",\"userId\":9}"), DEADLINE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider.publicWebQuery(9L, "trailing", "question", List.of(),
                route("model", "{\"action\":\"FINISH\"} {\"action\":\"FINISH\"}"), DEADLINE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publicWebQueryRejectsNonWebActionsAndPrivateObservations() {
        GatewayReActDecisionProvider provider = provider(new RecordingExecutor(), 6, 8, 100_000);

        assertThatThrownBy(() -> provider.publicWebQuery(9L, "non-web", "question", List.of(),
                route("model", """
                        {"action":"CALL","tool":"COMMUNITY_ARTICLES","query":"q"}
                        """), DEADLINE)).isInstanceOf(IllegalStateException.class);

        AtomicInteger invocations = new AtomicInteger();
        AgentToolObservation privateObservation = new AgentToolObservation(
                new AgentToolCall(AgentReadOnlyTool.LONG_TERM_MEMORY, "private query"),
                "SUCCESS", "private memory content");
        assertThatThrownBy(() -> provider.publicWebQuery(9L, "private-observation", "question",
                List.of(privateObservation), route("model", "{\"action\":\"FINISH\"}",
                        new AtomicReference<>(), invocations), DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(invocations).hasValue(0);
    }

    @Test
    void publicWebQueryAppliesTheRealSerializedTokenBudgetBeforeInvocation() {
        AgentContextProperties properties = new AgentContextProperties();
        properties.setWorkingWindowTokens(2_048);
        properties.setUnknownModelWindowTokens(2_048);
        properties.setMaxOutputTokens(512);
        properties.setSafetyMarginTokens(256);
        AgentPromptBudget promptBudget = new AgentPromptBudget(properties);
        AtomicReference<AiChatCommand> sent = new AtomicReference<>();
        GatewayReActDecisionProvider provider = provider(new RecordingExecutor(),
                Clock.fixed(NOW, ZoneOffset.UTC), 6, 8, 100_000, promptBudget);
        AgentToolObservation oversizedOld = new AgentToolObservation(new AgentToolCall(
                AgentReadOnlyTool.COMMUNITY_ARTICLES, "question"), "SUCCESS",
                "old-public-token ".repeat(2_000));
        AgentToolObservation newest = new AgentToolObservation(new AgentToolCall(
                AgentReadOnlyTool.WEB_SEARCH, "question"), "SUCCESS", "newest-public-evidence");

        provider.publicWebQuery(9L, "token-budget", "question", List.of(oversizedOld, newest),
                route("model", "{\"action\":\"FINISH\"}", sent, new AtomicInteger()), DEADLINE);

        String serializedPrompt = sent.get().messages().stream()
                .map(AiPromptMessage::text).reduce("", String::concat);
        assertThat(serializedPrompt).contains("newest-public-evidence")
                .doesNotContain("old-public-token");
        assertThat(serializedPrompt.length()).isLessThan(100_000);
        assertThat(promptBudget.estimate(sent.get().messages()))
                .isLessThanOrEqualTo(promptBudget.limits("model", UserAiFundingSource.USER)
                        .inputTokens());
    }

    @Test
    void parsesOneStrictCallAndSendsRealContextAndObservationsThroughFrozenRoute() {
        RecordingExecutor executor = new RecordingExecutor();
        AtomicReference<AiChatCommand> sent = new AtomicReference<>();
        AtomicInteger invocations = new AtomicInteger();
        PreparedUserAiChat frozenRoute = route("frozen-model", """
                {"action":"CALL","tool":"COMMUNITY_ARTICLES","query":"  新的检索词  "}
                """, sent, invocations);
        GatewayReActDecisionProvider provider = provider(executor, 6, 8, 100_000);
        AgentToolObservation observation = new AgentToolObservation(
                new AgentToolCall(AgentReadOnlyTool.COMMUNITY_ARTICLES, "旧检索词"),
                "SUCCESS", "真实证据 #A1");

        AgentReactDecision decision = provider.decide(9L, "request-7", "现在该查什么？",
                List.of(new AiPromptMessage(AiPromptRole.USER, "近期用户上下文"),
                        new AiPromptMessage(AiPromptRole.ASSISTANT, "近期助手上下文")),
                true, false, 2, 6, List.of(observation), frozenRoute, DEADLINE);

        assertThat(decision.call()).isEqualTo(new AgentToolCall(
                AgentReadOnlyTool.COMMUNITY_ARTICLES, "新的检索词"));
        assertThat(invocations).hasValue(1);
        assertThat(sent.get().capability()).isEqualTo(AiCapability.AGENT);
        assertThat(sent.get().responseMode()).isEqualTo(AiResponseMode.JSON_OBJECT);
        assertThat(sent.get().maxOutputTokens()).isEqualTo(512);
        String serializedPrompt = sent.get().messages().stream()
                .map(AiPromptMessage::text).reduce("", String::concat);
        assertThat(serializedPrompt)
                .contains("现在该查什么？", "近期用户上下文", "近期助手上下文",
                        "旧检索词", "SUCCESS", "真实证据 #A1");
        assertThat(executor.context.requestId()).isEqualTo("request-7:react:2");
        assertThat(executor.context.deadline()).isEqualTo(NOW.plusSeconds(5));
        assertThat(executor.context.inputCharacters()).isEqualTo(sent.get().messages().stream()
                .mapToInt(message -> message.text().length()).sum());
    }

    @Test
    void parsesOnlyTheExactFinishShape() {
        GatewayReActDecisionProvider provider = provider(new RecordingExecutor(), 6, 8, 100_000);

        AgentReactDecision decision = provider.decide(9L, "finish", "证据够了吗？", List.of(),
                true, true, 3, 4, List.of(), route("model", "{\"action\":\"FINISH\"}"), DEADLINE);

        assertThat(decision.call()).isNull();
    }

    @Test
    void rejectsExtraFieldsMalformedActionsAndUnauthorizedToolsInsteadOfFallingBack() {
        GatewayReActDecisionProvider provider = provider(new RecordingExecutor(), 6, 8, 100_000);

        assertThatThrownBy(() -> decide(provider, true, true,
                "{\"action\":\"FINISH\",\"reason\":\"enough\"}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> decide(provider, true, true,
                "{\"action\":\"CALL\",\"tool\":\"WEB_SEARCH\",\"query\":\"q\",\"url\":\"https://x\"}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> decide(provider, true, true,
                "{\"action\":\"finish\"}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> decide(provider, true, true,
                "{\"action\":\"FINISH\"} {\"action\":\"FINISH\"}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> decide(provider, false, true,
                "{\"action\":\"CALL\",\"tool\":\"LONG_TERM_MEMORY\",\"query\":\"q\"}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> decide(provider, false, true,
                "{\"action\":\"CALL\",\"tool\":\"CONVERSATION_HISTORY\",\"query\":\"q\"}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> decide(provider, true, false,
                "{\"action\":\"CALL\",\"tool\":\"WEB_SEARCH\",\"query\":\"q\"}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void trimsOldHistoryAndObservationsButKeepsQuestionAndNewestObservation() {
        AtomicReference<AiChatCommand> sent = new AtomicReference<>();
        String oversized = "old-material-".repeat(600);
        GatewayReActDecisionProvider provider = provider(new RecordingExecutor(), 6, 8, 3_600);
        List<AgentToolObservation> observations = List.of(
                new AgentToolObservation(new AgentToolCall(AgentReadOnlyTool.COMMUNITY_ARTICLES, "old-query"),
                        "SUCCESS", oversized),
                new AgentToolObservation(new AgentToolCall(AgentReadOnlyTool.WEB_SEARCH, "latest-query"),
                        "SUCCESS", "latest-observation"));

        provider.decide(9L, "trim", "must-keep-question",
                List.of(new AiPromptMessage(AiPromptRole.USER, oversized)), true, true, 3, 4,
                observations, route("model", "{\"action\":\"FINISH\"}", sent,
                        new AtomicInteger()), DEADLINE);

        String serializedPrompt = sent.get().messages().stream()
                .map(AiPromptMessage::text).reduce("", String::concat);
        assertThat(serializedPrompt).contains("must-keep-question", "latest-query", "latest-observation")
                .doesNotContain("old-material-", "old-query");
        assertThat(serializedPrompt.length()).isLessThanOrEqualTo(3_600);
    }

    @Test
    void failsClearlyWhenTheRequiredInputCannotFitAndNeverCallsTheRoute() {
        AtomicInteger invocations = new AtomicInteger();
        GatewayReActDecisionProvider provider = provider(new RecordingExecutor(), 6, 8, 1_000);

        assertThatThrownBy(() -> provider.decide(9L, "oversized", "q".repeat(5_000), List.of(),
                true, true, 1, 8, List.of(),
                route("model", "{\"action\":\"FINISH\"}", new AtomicReference<>(), invocations),
                DEADLINE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget");
        assertThat(invocations).hasValue(0);
    }

    @Test
    void rejectsInvalidLimitsAndNonStoppingOrWrongModelResponses() {
        assertThatThrownBy(() -> provider(new RecordingExecutor(), 17, 8, 100_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider(new RecordingExecutor(), 6, 25, 100_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider(new RecordingExecutor(), 0, 8, 100_000))
                .isInstanceOf(IllegalArgumentException.class);

        GatewayReActDecisionProvider provider = provider(new RecordingExecutor(), 6, 8, 100_000);
        PreparedUserAiChat lengthRoute = new PreparedUserAiChat("model", UserAiFundingSource.USER,
                command -> new UserAiRoutedResult(
                        new AiChatResult("{\"action\":\"FINISH\"}", "length", 1, 1,
                                "test", "model"), UserAiFundingSource.USER));
        PreparedUserAiChat wrongModelRoute = new PreparedUserAiChat("model", UserAiFundingSource.USER,
                command -> new UserAiRoutedResult(
                        new AiChatResult("{\"action\":\"FINISH\"}", "stop", 1, 1,
                                "test", "other"), UserAiFundingSource.USER));

        assertThatThrownBy(() -> provider.decide(9L, "length", "q", List.of(), true, true,
                1, 8, List.of(), lengthRoute, DEADLINE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider.decide(9L, "model", "q", List.of(), true, true,
                1, 8, List.of(), wrongModelRoute, DEADLINE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rechecksDeadlineAfterPromptSerializationAndAfterTheRouteReturns() {
        AtomicInteger beforeRouteInvocations = new AtomicInteger();
        GatewayReActDecisionProvider expiresBeforeRoute = provider(new RecordingExecutor(),
                new SequenceClock(NOW, DEADLINE.plusMillis(1)), 6, 8, 100_000);

        assertThatThrownBy(() -> expiresBeforeRoute.decide(9L, "before-route", "q", List.of(),
                true, true, 1, 8, List.of(), route("model", "{\"action\":\"FINISH\"}",
                        new AtomicReference<>(), beforeRouteInvocations), DEADLINE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deadline");
        assertThat(beforeRouteInvocations).hasValue(0);

        AtomicInteger afterRouteInvocations = new AtomicInteger();
        GatewayReActDecisionProvider expiresAfterRoute = provider(new RecordingExecutor(),
                new SequenceClock(NOW, NOW.plusSeconds(1), DEADLINE.plusMillis(1)),
                6, 8, 100_000);

        assertThatThrownBy(() -> expiresAfterRoute.decide(9L, "after-route", "q", List.of(),
                true, true, 1, 8, List.of(), route("model", "{\"action\":\"FINISH\"}",
                        new AtomicReference<>(), afterRouteInvocations), DEADLINE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deadline");
        assertThat(afterRouteInvocations).hasValue(1);
    }

    @Test
    void anInterruptRaisedDuringTheRouteCannotBecomeFinish() {
        AtomicBoolean routeCalled = new AtomicBoolean();
        PreparedUserAiChat interruptingRoute = new PreparedUserAiChat("model",
                UserAiFundingSource.USER, command -> {
            routeCalled.set(true);
            Thread.currentThread().interrupt();
            return new UserAiRoutedResult(new AiChatResult("{\"action\":\"FINISH\"}", "stop",
                    1, 1, "test", "model"), UserAiFundingSource.USER);
        });
        try {
            assertThatThrownBy(() -> provider(new RecordingExecutor(), 6, 8, 100_000)
                    .decide(9L, "interrupt", "q", List.of(), true, true, 1, 8, List.of(),
                            interruptingRoute, DEADLINE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("interrupted");
            assertThat(routeCalled).isTrue();
        } finally {
            // 清理测试线程上的中断位，避免污染后续 JUnit 用例。
            Thread.interrupted();
        }
    }

    private AgentReactDecision decide(GatewayReActDecisionProvider provider,
                                      boolean persistentAllowed, boolean webEnabled,
                                      String response) {
        return provider.decide(9L, "invalid", "question", List.of(), persistentAllowed, webEnabled,
                1, 8, List.of(), route("model", response), DEADLINE);
    }

    private GatewayReActDecisionProvider provider(RecordingExecutor executor, int maxRounds,
                                                   int maxToolCalls, int maxInputCharacters) {
        return provider(executor, Clock.fixed(NOW, ZoneOffset.UTC), maxRounds, maxToolCalls,
                maxInputCharacters);
    }

    private GatewayReActDecisionProvider provider(RecordingExecutor executor, Clock clock,
                                                   int maxRounds, int maxToolCalls,
                                                   int maxInputCharacters) {
        return provider(executor, clock, maxRounds, maxToolCalls, maxInputCharacters,
                new AgentPromptBudget(new AgentContextProperties()));
    }

    private GatewayReActDecisionProvider provider(RecordingExecutor executor, Clock clock,
                                                   int maxRounds, int maxToolCalls,
                                                   int maxInputCharacters,
                                                   AgentPromptBudget promptBudget) {
        return new GatewayReActDecisionProvider(executor, new ObjectMapper(), clock,
                Duration.ofSeconds(5), maxRounds, maxToolCalls,
                promptBudget, maxInputCharacters);
    }

    private PreparedUserAiChat route(String model, String response) {
        return route(model, response, new AtomicReference<>(), new AtomicInteger());
    }

    private PreparedUserAiChat route(String model, String response,
                                     AtomicReference<AiChatCommand> sent,
                                     AtomicInteger invocations) {
        return new PreparedUserAiChat(model, UserAiFundingSource.USER, command -> {
            sent.set(command);
            invocations.incrementAndGet();
            return new UserAiRoutedResult(new AiChatResult(response, "stop", 20, 10,
                    "test", model), UserAiFundingSource.USER);
        });
    }

    private static final class RecordingExecutor implements AiCapabilityExecutor {
        private AiInvocationContext context;

        @Override
        public <T> T execute(AiInvocationContext context, CheckedSupplier<T> operation) {
            this.context = context;
            try {
                return operation.get();
            } catch (Throwable error) {
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(error);
            }
        }

        @Override
        public <A, T> T execute(AiInvocationContext context, AttemptObserver<A, T> observer,
                                AttemptOperation<A, T> operation) {
            throw new UnsupportedOperationException();
        }
    }

    /** 每次读取时间时向前推进，用来模拟序列化和网关请求消耗的时间。 */
    private static final class SequenceClock extends Clock {
        private final List<Instant> instants;
        private int index;

        private SequenceClock(Instant... instants) {
            this.instants = List.of(instants);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant value = instants.get(Math.min(index, instants.size() - 1));
            index++;
            return value;
        }
    }
}
