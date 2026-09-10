package cumt.zongzuo.community.ai.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import cumt.zongzuo.community.ai.agent.retrieval.*;
import cumt.zongzuo.community.ai.provider.*;
import cumt.zongzuo.community.ai.runtime.*;
import cumt.zongzuo.community.ai.userprovider.*;

import io.github.resilience4j.core.functions.CheckedSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class NativeReActToolTest {
    @Test
    void nativeToolCallExecutesAndReturnsItsResultToTheNextModelRequest() {
        var queries = new ArrayList<String>();
        var retrieval = mock(HybridArticleRetrievalService.class);
        when(retrieval.retrieve(any()))
                .thenAnswer(
                        call -> {
                            ArticleRetrievalQuery query = call.getArgument(0);
                            queries.add(query.query());
                            return new ArticleRetrievalResult(
                                    0, 0, true, true, List.of(), List.of());
                        });
        AtomicInteger steps = new AtomicInteger();
        ChatModel model =
                prompt -> {
                    int step = steps.incrementAndGet();
                    if (step == 1)
                        return response(
                                AssistantMessage.builder()
                                        .content(null)
                                        .toolCalls(
                                                List.of(
                                                        new AssistantMessage.ToolCall(
                                                                "call-1",
                                                                "function",
                                                                "COMMUNITY_ARTICLES",
                                                                "{\"query\":\"refined\"}")))
                                        .build(),
                                "tool_calls");
                    assertThat(prompt.getInstructions())
                            .anySatisfy(
                                    message -> {
                                        assertThat(message).isInstanceOf(ToolResponseMessage.class);
                                        assertThat(((ToolResponseMessage) message).getResponses())
                                                .anySatisfy(
                                                        value ->
                                                                assertThat(value.id())
                                                                        .isEqualTo("call-1"));
                                    });
                    return response(new AssistantMessage("检索结束"), "stop");
                };
        UserAiChatRouter router =
                new UserAiChatRouter() {
                    public UserAiRoutedResult generate(long id, AiChatCommand command) {
                        return new UserAiRoutedResult(
                                new AiChatResult(
                                        "{\"answer\":\"普通回答\",\"citations\":[]}",
                                        "stop",
                                        2,
                                        2,
                                        "test",
                                        "model"),
                                UserAiFundingSource.USER);
                    }

                    public PreparedUserAiChat prepare(long id, String ignored) {
                        return new PreparedUserAiChat(
                                "model",
                                UserAiFundingSource.USER,
                                command -> generate(id, command),
                                () -> {},
                                (command, observer) -> generate(id, command),
                                model);
                    }
                };
        var service =
                new GroundedAnswerService(
                        retrieval,
                        new DirectExecutor(),
                        router,
                        new GroundedAnswerParser(new ObjectMapper()),
                        Clock.systemUTC(),
                        "model",
                        Duration.ofSeconds(20),
                        null,
                        null,
                        false,
                        null);
        service.answerTemporary(9, "native", "question", List.of(), Instant.now().plusSeconds(20));
        assertThat(queries).containsExactly("question", "refined");
        assertThat(steps).hasValue(2);
    }

    static ChatResponse response(AssistantMessage message, String finish) {
        return new ChatResponse(
                List.of(
                        new Generation(
                                message,
                                ChatGenerationMetadata.builder().finishReason(finish).build())),
                ChatResponseMetadata.builder()
                        .model("model")
                        .usage(new DefaultUsage(2, 2, 4))
                        .build());
    }

    static class DirectExecutor implements AiCapabilityExecutor {
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
