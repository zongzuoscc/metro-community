package cumt.zongzuo.community.ai.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.context.*;
import cumt.zongzuo.community.ai.agent.history.*;
import cumt.zongzuo.community.ai.agent.memory.*;
import cumt.zongzuo.community.ai.agent.memory.index.AgentMemoryVectorService;
import cumt.zongzuo.community.ai.agent.react.*;
import cumt.zongzuo.community.ai.agent.retrieval.*;
import cumt.zongzuo.community.ai.provider.*;
import cumt.zongzuo.community.ai.userprovider.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** 捕获真实 ReactAgent 发出的下一次模型请求及最终流式请求，写服务替身只模拟提交边界。 */
class AgeMemAgentIntegrationTest {
    @Test
    void selfCommittedMemoryIsImmediatelyVisibleAndDoesNotCancelFinalStreaming() {
        var f = new Fixture();
        var memory = memory("prefers concise Chinese", 1);
        when(f.tools.add(eq(9L), eq(f.run), eq(0L), eq("PREFERENCE"), anyString(), anyString()))
                .thenAnswer(call -> { f.epoch.set(1); return new AgentMemoryToolService.Mutation("ADDED", memory, 0, 1, "PENDING"); });
        f.model = prompt -> f.prompts.size() == 1
                ? tool("ADD", "{\"category\":\"PREFERENCE\",\"content\":\"prefers concise Chinese\",\"sourceQuote\":\"我喜欢简短中文\"}")
                : done();
        var answer = f.answer();
        assertThat(f.prompts.get(1).getContents()).contains("prefers concise Chinese", "index=PENDING");
        assertThat(f.finalPrompt.get()).contains("prefers concise Chinese");
        assertThat(answer.memoryUses()).extracting(AgentMemoryUse::content).containsExactly("prefers concise Chinese");
        assertThat(f.streamedBeforeCompletion).isTrue();
        verify(f.memories, never()).recall(anyLong(), anyString(), anyInt(), any());
    }

    @Test
    void concurrentExternalEditAfterOurCommitStillCancelsTheTurn() {
        var f = new Fixture();
        when(f.tools.add(anyLong(), any(), anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    f.epoch.set(2); // 本次提交0→1，另一个人工提交又推进到2。
                    return new AgentMemoryToolService.Mutation("ADDED", memory("mine", 1), 0, 1, "PENDING");
                });
        f.model = prompt -> tool("ADD", "{\"category\":\"PREFERENCE\",\"content\":\"mine\",\"sourceQuote\":\"我喜欢简短中文\"}");
        assertThatThrownBy(f::answer).isInstanceOf(CancellationException.class);
        assertThat(f.prompts).hasSize(1);
        assertThat(f.finalPrompt.get()).isNull();
    }

    @Test
    void updateAndDeleteRemoveOldVersionsFromLaterModelAndFinalInputs() {
        var f = new Fixture();
        when(f.memories.recall(anyLong(), anyString(), anyInt(), any())).thenReturn(List.of(memory("obsolete-memory-text", 1)));
        when(f.memories.indexStatus(9)).thenReturn(AgentMemoryVectorService.IndexStatus.CURRENT);
        when(f.tools.update(eq(9L), eq(f.run), eq(0L), eq(4L), eq(1L), anyString(), anyString()))
                .thenAnswer(call -> { f.epoch.set(1); return new AgentMemoryToolService.Mutation("UPDATED", memory("revised-memory-text", 2), 0, 1, "PENDING"); });
        when(f.tools.delete(eq(9L), eq(f.run), eq(1L), eq(4L), eq(2L), anyString()))
                .thenAnswer(call -> { f.epoch.set(2); return new AgentMemoryToolService.Mutation("DELETED", null, 1, 2, "DELETING"); });
        f.model = prompt -> switch (f.prompts.size()) {
            case 1 -> tool("RETRIEVE", "{\"query\":\"preferences\"}");
            case 2 -> tool("UPDATE", "{\"memoryId\":4,\"expectedVersion\":1,\"content\":\"revised-memory-text\",\"sourceQuote\":\"我喜欢简短中文\"}");
            case 3 -> {
                assertThat(prompt.getContents()).contains("revised-memory-text").doesNotContain("obsolete-memory-text");
                assertThat(prompt.getInstructions().toString()).doesNotContain("obsolete-memory-text");
                yield tool("DELETE", "{\"memoryId\":4,\"expectedVersion\":2,\"sourceQuote\":\"删除这条记忆\"}");
            }
            default -> {
                assertThat(prompt.getInstructions().toString()).doesNotContain("obsolete-memory-text", "revised-memory-text");
                yield done();
            }
        };
        var answer = f.answer();
        assertThat(answer.memoryUses()).isEmpty();
        assertThat(f.finalPrompt.get()).doesNotContain("obsolete-memory-text", "revised-memory-text");
    }

    @Test
    void disabledMemoryAllowsContextToolsButExposesNoPersistentMemoryTools() {
        var f = new Fixture();
        when(f.tools.enabled(9)).thenReturn(false);
        f.model = prompt -> {
            var names = ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().stream()
                    .map(c -> c.getToolDefinition().name()).toList();
            assertThat(names).contains("SUMMARY", "FILTER").doesNotContain("ADD", "UPDATE", "DELETE", "RETRIEVE", "LONG_TERM_MEMORY");
            return done();
        };
        f.answer();
        verify(f.tools, never()).add(anyLong(), any(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void emptyRecallWithUnfinishedProjectionIsReportedAsPending() {
        var f = new Fixture();
        when(f.memories.recall(anyLong(), anyString(), anyInt(), any())).thenReturn(List.of());
        when(f.memories.indexStatus(9)).thenReturn(AgentMemoryVectorService.IndexStatus.PENDING);
        f.model = prompt -> f.prompts.size() == 1 ? tool("RETRIEVE", "{\"query\":\"preferences\"}") : done();
        f.answer();
        assertThat(f.prompts.get(1).getContents()).contains("PENDING", "not absence");
        assertThat(f.finalPrompt.get()).contains("RETRIEVE=PENDING");
    }

    @Test
    void noMemoryCallIsRequiredForAnOrdinaryAnswer() {
        var f = new Fixture();
        f.answer();
        assertThat(f.prompts).hasSize(1);
        verify(f.memories, never()).recall(anyLong(), anyString(), anyInt(), any());
        verify(f.tools, never()).add(anyLong(), any(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void aWriteSkippedByTheToolLimitRemainsAnUnexecutedReceiptInTheFinalPrompt() {
        var f = new Fixture();
        f.toolLimit = 2; // 初始站内检索 + 第一个 ADD，第二个 ADD 必须留下未执行回执。
        when(f.tools.add(anyLong(), any(), anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> { f.epoch.set(1); return new AgentMemoryToolService.Mutation("ADDED", memory("first fact", 1), 0, 1, "PENDING"); });
        f.model = prompt -> NativeReActToolTest.response(AssistantMessage.builder().content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("first", "function", "ADD",
                                "{\"category\":\"PREFERENCE\",\"content\":\"first fact\",\"sourceQuote\":\"我喜欢简短中文\"}"),
                        new AssistantMessage.ToolCall("second", "function", "ADD",
                                "{\"category\":\"PREFERENCE\",\"content\":\"second fact\",\"sourceQuote\":\"我喜欢简短中文\"}")))
                .build(), "tool_calls");
        f.answer();
        verify(f.tools, times(1)).add(anyLong(), any(), anyLong(), anyString(), anyString(), anyString());
        assertThat(f.finalPrompt.get()).contains("ADD=ADDED(memoryId=4, version=1, index=PENDING)",
                "ADD=LIMIT", "not executed");
    }

    private static AgentMemoryView memory(String text, long version) {
        return new AgentMemoryView(4, "PREFERENCE", text, version, "ACTIVE", null, "CONVERSATION");
    }
    private static ChatResponse tool(String name, String arguments) {
        return NativeReActToolTest.response(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + name, "function", name, arguments))).build(), "tool_calls");
    }
    private static ChatResponse done() { return NativeReActToolTest.response(new AssistantMessage("done"), "stop"); }

    private static final class Fixture {
        final UUID run = UUID.randomUUID();
        final AtomicLong epoch = new AtomicLong();
        final AgentMemoryRecallService memories = mock(AgentMemoryRecallService.class);
        final AgentMemoryToolService tools = mock(AgentMemoryToolService.class);
        final AgentCompactionGraph compaction = mock(AgentCompactionGraph.class);
        final HybridArticleRetrievalService retrieval = mock(HybridArticleRetrievalService.class);
        final AgentConversationHistorySearchService history = mock(AgentConversationHistorySearchService.class);
        final List<Prompt> prompts = new ArrayList<>();
        final AtomicReference<String> finalPrompt = new AtomicReference<>();
        final StringBuilder received = new StringBuilder();
        boolean streamedBeforeCompletion;
        int toolLimit = 12;
        Function<Prompt, ChatResponse> model = prompt -> done();
        final Clock clock = Clock.systemUTC();
        final Instant deadline = clock.instant().plusSeconds(30);
        Fixture() {
            when(tools.enabled(9)).thenReturn(true);
            when(memories.epoch(9)).thenAnswer(call -> epoch.get());
            when(retrieval.retrieve(any())).thenReturn(new ArticleRetrievalResult(0, 0, true, true, List.of(), List.of()));
            when(compaction.prepare(anyLong(), any(), anyString(), any(), any(), any()))
                    .thenReturn(new AgentCompactionGraph.Prepared(AgentConversationPage.empty(), List.of()));
        }
        GroundedAgentAnswer answer() {
            var executor = new NativeReActToolTest.DirectExecutor();
            var budget = new AgentPromptBudget(new AgentContextProperties());
            var route = new PreparedUserAiChat("model", UserAiFundingSource.USER,
                    command -> { finalPrompt.set(command.messages().toString()); return result(); }, () -> {},
                    (command, observer) -> {
                        finalPrompt.set(command.messages().toString());
                        observer.onDelta("{\"answer\":\"逐字回答");
                        streamedBeforeCompletion = received.toString().equals("逐字回答");
                        observer.onDelta("\",\"citations\":[]}");
                        return result();
                    }, prompt -> { prompts.add(prompt); return model.apply(prompt); });
            var router = mock(UserAiChatRouter.class);
            when(router.prepare(9, "model")).thenReturn(route);
            var service = new GroundedAnswerService(retrieval, executor, router, new GroundedAnswerParser(new ObjectMapper()),
                    clock, "model", Duration.ofSeconds(20), memories, history, true, null,
                    new NativeAgentRuntime(executor, new ObjectMapper(), clock, Duration.ofSeconds(5), 8, toolLimit, budget, 100_000),
                    budget, 100_000, compaction, tools);
            return service.answerPersistent(9, run, "我喜欢简短中文，也可能需要删除这条记忆", false, deadline, () -> true, received::append);
        }
        private static UserAiRoutedResult result() {
            return new UserAiRoutedResult(new AiChatResult("{\"answer\":\"逐字回答\",\"citations\":[]}",
                    "stop", 3, 4, "test", "model"), UserAiFundingSource.USER);
        }
    }
}
