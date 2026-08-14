package cumt.zongzuo.community.ai.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BoundedReadOnlyAgentPlannerTest {

    private static final Instant DEADLINE = Instant.parse("2026-08-14T00:01:00Z");

    private final AiChatGateway gateway = mock(AiChatGateway.class);

    @Test
    void mainConversationUsesOnlyWhitelistedToolsAndForcesSiteBeforeEnabledWebSearch() {
        when(gateway.generate(any())).thenReturn(result("""
                {"tools":["DELETE_ARTICLE","CONVERSATION_HISTORY","LONG_TERM_MEMORY"],
                 "reviewAfterExecution":false}
                """));

        AgentPlannerRound round = planner().plan(9L, "request-main", "我之前说过什么？",
                true, true, 1, Set.of(), AgentPlannerEvidence.empty(), DEADLINE);

        assertThat(round.tools()).containsExactly(
                AgentReadOnlyTool.COMMUNITY_ARTICLES,
                AgentReadOnlyTool.LONG_TERM_MEMORY,
                AgentReadOnlyTool.CONVERSATION_HISTORY,
                AgentReadOnlyTool.WEB_SEARCH);
        assertThat(round.tools()).doesNotContainNull();
    }

    @Test
    void temporaryConversationCannotRequestPersistentMemoryOrHistory() {
        when(gateway.generate(any())).thenReturn(result("""
                {"tools":["LONG_TERM_MEMORY","CONVERSATION_HISTORY"],
                 "reviewAfterExecution":true}
                """));

        AgentPlannerRound round = planner().plan(9L, "request-temporary", "我以前喜欢什么？",
                false, true, 1, Set.of(), AgentPlannerEvidence.empty(), DEADLINE);

        assertThat(round.tools()).containsExactly(
                AgentReadOnlyTool.COMMUNITY_ARTICLES,
                AgentReadOnlyTool.WEB_SEARCH);
    }

    @Test
    void secondRoundCannotRepeatToolsAndCannotExceedTheRemainingBudget() {
        when(gateway.generate(any())).thenReturn(result("""
                {"tools":["COMMUNITY_ARTICLES","LONG_TERM_MEMORY",
                           "CONVERSATION_HISTORY","WEB_SEARCH"],
                 "reviewAfterExecution":true}
                """));

        AgentPlannerRound round = planner().plan(9L, "request-second", "继续补查",
                true, true, 2,
                Set.of(AgentReadOnlyTool.COMMUNITY_ARTICLES,
                        AgentReadOnlyTool.LONG_TERM_MEMORY,
                        AgentReadOnlyTool.WEB_SEARCH),
                new AgentPlannerEvidence(0, 0, 0, 0, 0), DEADLINE);

        assertThat(round.tools()).containsExactly(AgentReadOnlyTool.CONVERSATION_HISTORY);
    }

    @Test
    void malformedOrUnsafeProviderPlanFallsBackToTheSameBoundedReadOnlySet() {
        when(gateway.generate(any())).thenThrow(new IllegalStateException("provider unavailable"));

        AgentPlannerRound round = planner().plan(9L, "request-fallback", "帮我综合查一下",
                true, true, 1, Set.of(), AgentPlannerEvidence.empty(), DEADLINE);

        assertThat(round.tools()).containsExactly(
                AgentReadOnlyTool.COMMUNITY_ARTICLES,
                AgentReadOnlyTool.LONG_TERM_MEMORY,
                AgentReadOnlyTool.CONVERSATION_HISTORY,
                AgentReadOnlyTool.WEB_SEARCH);
        assertThat(round.reviewAfterExecution()).isFalse();
    }

    @Test
    void mandatoryWebSearchCannotBeDisplacedWhenToolBudgetIsReducedToTwo() {
        when(gateway.generate(any())).thenReturn(result("""
                {"tools":["LONG_TERM_MEMORY","CONVERSATION_HISTORY"],
                 "reviewAfterExecution":false}
                """));
        UserAiChatRouter router = (userId, command) -> new UserAiRoutedResult(
                gateway.generate(command), UserAiFundingSource.USER);
        BoundedReadOnlyAgentPlanner reduced = new BoundedReadOnlyAgentPlanner(
                new DirectExecutor(), router, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(6), 2, 2);

        AgentPlannerRound round = reduced.plan(9L, "request-two", "综合查询",
                true, true, 1, Set.of(), AgentPlannerEvidence.empty(), DEADLINE);

        assertThat(round.tools()).containsExactly(
                AgentReadOnlyTool.COMMUNITY_ARTICLES, AgentReadOnlyTool.WEB_SEARCH);
    }

    private BoundedReadOnlyAgentPlanner planner() {
        UserAiChatRouter router = (userId, command) -> new UserAiRoutedResult(
                gateway.generate(command), UserAiFundingSource.USER);
        return new BoundedReadOnlyAgentPlanner(new DirectExecutor(), router,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(6), 2, 4);
    }

    private static AiChatResult result(String text) {
        return new AiChatResult(text, "stop", 40, 12, "test", "qwen-test");
    }

    private static final class DirectExecutor implements AiCapabilityExecutor {
        @Override
        public <T> T execute(cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                             CheckedSupplier<T> operation) {
            assertThat(context.capability()).isEqualTo(AiCapability.AGENT);
            try {
                return operation.get();
            } catch (Throwable error) {
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(error);
            }
        }

        @Override
        public <A, T> T execute(cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                                AttemptObserver<A, T> observer,
                                AttemptOperation<A, T> operation) {
            throw new UnsupportedOperationException();
        }
    }
}
