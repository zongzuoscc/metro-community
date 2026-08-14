package cumt.zongzuo.community.ai.agent.history;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import cumt.zongzuo.community.ai.userprovider.UserAiChatRouter;
import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;
import cumt.zongzuo.community.ai.userprovider.UserAiRoutedResult;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEpisodeSummaryGeneratorTest {

    @Test
    void summarizesASealedEpisodeThroughTheGuardedMemoryCapability() {
        AtomicReference<String> prompt = new AtomicReference<>();
        UserAiChatRouter router = (userId, command) -> {
            assertThat(command.capability()).isEqualTo(AiCapability.MEMORY_EXTRACTION);
            prompt.set(command.messages().getLast().text());
            return new UserAiRoutedResult(new AiChatResult(
                    " 用户正在准备 Java 后端面试；偏好简洁回答。 ", "stop",
                    20, 12, "qwen", "qwen-plus"), UserAiFundingSource.PLATFORM);
        };
        AgentEpisodeSummaryGenerator generator = new AgentEpisodeSummaryGenerator(
                new DirectExecutor(), router,
                Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(20), 2_000);

        String summary = generator.generate(7L, 31L, List.of(
                new AgentEpisodeMessage("USER", "我最近准备 Java 后端面试"),
                new AgentEpisodeMessage("ASSISTANT", "可以从并发和数据库开始")));

        assertThat(summary).isEqualTo("用户正在准备 Java 后端面试；偏好简洁回答。");
        assertThat(prompt.get()).contains("USER: 我最近准备 Java 后端面试")
                .contains("ASSISTANT: 可以从并发和数据库开始");
    }

    private static final class DirectExecutor implements AiCapabilityExecutor {
        @Override
        public <T> T execute(AiInvocationContext context, CheckedSupplier<T> operation) {
            assertThat(context.capability()).isEqualTo(AiCapability.MEMORY_EXTRACTION);
            try {
                return operation.get();
            } catch (Throwable error) {
                throw new RuntimeException(error);
            }
        }

        @Override
        public <A, T> T execute(AiInvocationContext context, AttemptObserver<A, T> observer,
                                AttemptOperation<A, T> operation) {
            throw new UnsupportedOperationException();
        }
    }
}
