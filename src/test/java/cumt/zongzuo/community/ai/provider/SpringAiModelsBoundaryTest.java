package cumt.zongzuo.community.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class SpringAiModelsBoundaryTest {
    @Test
    void totalResponseIncludingWhitespaceIsBoundedAndUpstreamCancelledImmediately() {
        var produced = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        var observed = new ArrayList<String>();
        var source =
                Flux.range(0, 20)
                        .map(
                                index -> {
                                    produced.incrementAndGet();
                                    return new ChatResponse(
                                            List.of(
                                                    new Generation(
                                                            new AssistantMessage(" ".repeat(65536)),
                                                            ChatGenerationMetadata.builder()
                                                                    .finishReason("stop")
                                                                    .build())),
                                            ChatResponseMetadata.builder()
                                                    .model("test-model")
                                                    .build());
                                })
                        .doOnCancel(() -> cancelled.set(true));
        assertThatThrownBy(
                        () ->
                                SpringAiModels.stream(
                                        source, "test", "test-model", true, observed::add))
                .isInstanceOfSatisfying(
                        AiProviderException.class,
                        error ->
                                assertThat(error.reason())
                                        .isEqualTo(AiProviderErrorReason.MALFORMED_RESPONSE));
        assertThat(produced).hasValue(3);
        assertThat(cancelled).isTrue();
        assertThat(observed.stream().mapToInt(String::length).sum()).isEqualTo(131072);
    }
}
