package cumt.zongzuo.community.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.context.AgentCompactionGraph;
import cumt.zongzuo.community.ai.agent.context.AgentContextProperties;
import cumt.zongzuo.community.ai.agent.context.AgentPromptBudget;
import cumt.zongzuo.community.ai.agent.history.AgentConversationHistorySearchService;
import cumt.zongzuo.community.ai.agent.history.AgentConversationPage;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryRecallService;
import cumt.zongzuo.community.ai.agent.retrieval.ArticleRetrievalResult;
import cumt.zongzuo.community.ai.agent.retrieval.HybridArticleRetrievalService;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSearchGateway;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSearchResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.userprovider.PreparedUserAiChat;
import cumt.zongzuo.community.ai.userprovider.UserAiChatRouter;
import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroundedAnswerLegacyCancellationTest {
    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<String> calls = new ArrayList<>();
    private final HybridArticleRetrievalService retrieval = mock(HybridArticleRetrievalService.class);
    private final AgentMemoryRecallService memories = mock(AgentMemoryRecallService.class);
    private final AgentConversationHistorySearchService history = mock(AgentConversationHistorySearchService.class);
    private final AgentWebSearchGateway web = mock(AgentWebSearchGateway.class);
    private final AiCapabilityExecutor executor = mock(AiCapabilityExecutor.class);

    @Test
    void disabledPlannerDoesNotStartWebSearchAfterTemporaryTurnCancellation() {
        when(retrieval.retrieve(any())).thenAnswer(invocation -> {
            calls.add("articles");
            running.set(false);
            return new ArticleRetrievalResult(0, 0, true, true, List.of(), List.of());
        });
        when(web.search(any(), any())).thenAnswer(invocation -> {
            calls.add("web");
            return AgentWebSearchResult.empty();
        });

        assertThatThrownBy(() -> service().answerTemporary(9L, "cancel", "问题", List.of(),
                true, NOW.plusSeconds(30), running::get)).isInstanceOf(CancellationException.class);
        assertThat(calls).containsExactly("articles");
        verifyNoInteractions(executor, memories, history);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void disabledPlannerStopsPersistentToolsAtEachCancellationBoundary(int cancelAfter) {
        when(retrieval.retrieve(any())).thenAnswer(invocation -> {
            completed("articles", cancelAfter);
            return new ArticleRetrievalResult(0, 0, true, true, List.of(), List.of());
        });
        when(memories.recall(anyLong(), any(), anyInt(), any())).thenAnswer(invocation -> {
            completed("memories", cancelAfter);
            return List.of();
        });
        when(history.search(anyLong(), any(), anyInt())).thenAnswer(invocation -> {
            completed("history", cancelAfter);
            return List.of();
        });
        when(web.search(any(), any())).thenAnswer(invocation -> {
            completed("web", cancelAfter);
            return AgentWebSearchResult.empty();
        });

        assertThatThrownBy(() -> service().answerPersistent(9L, UUID.randomUUID(), "问题", true,
                NOW.plusSeconds(30), running::get)).isInstanceOf(CancellationException.class);
        assertThat(calls).containsExactlyElementsOf(switch (cancelAfter) {
            case 1 -> List.of("articles");
            case 2 -> List.of("articles", "memories");
            default -> List.of("articles", "memories", "history");
        });
        verifyNoInteractions(executor);
    }

    private void completed(String tool, int cancelAfter) {
        calls.add(tool);
        if (calls.size() == cancelAfter) running.set(false);
    }

    private GroundedAnswerService service() {
        var router = mock(UserAiChatRouter.class);
        when(router.prepare(9L, "test-model")).thenReturn(new PreparedUserAiChat("test-model",
                UserAiFundingSource.PLATFORM, command -> { throw new AssertionError("Cancelled turn invoked provider"); }));
        var graph = mock(AgentCompactionGraph.class);
        when(graph.prepare(anyLong(), any(), any(), any(), any())).thenReturn(
                new AgentCompactionGraph.Prepared(AgentConversationPage.empty(), List.of()));
        return new GroundedAnswerService(retrieval, executor, router,
                new GroundedAnswerParser(new ObjectMapper()), Clock.fixed(NOW, ZoneOffset.UTC),
                "test-model", Duration.ofSeconds(20), memories, history, true, web, null,
                new AgentPromptBudget(new AgentContextProperties()), 400_000, graph);
    }
}
