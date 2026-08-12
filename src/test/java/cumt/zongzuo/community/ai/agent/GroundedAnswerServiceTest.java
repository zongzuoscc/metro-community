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
import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatGateway;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
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
    void insufficientCommunityEvidenceReturnsWithoutCallingTheModel() {
        retrievalResult(List.of());

        GroundedAgentAnswer answer = service().answer(9L, "request-3", "unknown",
                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.answer()).isEqualTo("现有社区资料不足，暂时无法给出有引用的回答。");
        assertThat(answer.citations()).isEmpty();
        verify(gateway, never()).generate(any());
    }

    @Test
    void disabledMemoryIsNeitherReadNorSentToTheModel() {
        retrievalResult(List.of());
        GroundedAgentAnswer answer = service(false).answer(9L, "request-memory-off", "你记得我吗",
                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.finishReason()).isEqualTo("insufficient_evidence");
        verify(memories, never()).recall(any(Long.class), any(), any(Integer.class));
        verify(gateway, never()).generate(any());
    }

    @Test
    void answersFromOwnerMemoryAndOldConversationWithoutCommunityCitations() {
        retrievalResult(List.of());
        when(memories.recall(9L, "你记得我喜欢什么，以及我说过的重话吗？", 6))
                .thenReturn(List.of(new AgentMemoryView(71L, "PREFERENCE",
                        "我喜欢简洁的回答风格", 2L, "ACTIVE")));
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

    private void retrievalResult(List<ResolvedArticleChunk> chunks) {
        when(retrieval.retrieve(any(ArticleRetrievalQuery.class))).thenReturn(new ArticleRetrievalResult(
                chunks.size(), chunks.size(), true, true, chunks,
                chunks.stream().map(chunk -> new RankedArticleChunk(chunk, .03, 1, 1)).toList()));
    }

    private GroundedAnswerService service() {
        return service(true);
    }

    private GroundedAnswerService service(boolean memoryEnabled) {
        return new GroundedAnswerService(retrieval, new DirectExecutor(), gateway,
                new GroundedAnswerParser(new ObjectMapper()),
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                "deepseek-test", Duration.ofSeconds(30), memories, history, memoryEnabled);
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
