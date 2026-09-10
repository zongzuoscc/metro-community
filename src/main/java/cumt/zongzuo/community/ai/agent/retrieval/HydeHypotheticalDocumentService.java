package cumt.zongzuo.community.ai.agent.retrieval;

import com.alibaba.cloud.ai.rag.preretrieval.transformation.HyDeTransformer;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatCommand;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.ai.provider.AiPromptMessage;
import cumt.zongzuo.community.ai.provider.AiPromptRole;
import cumt.zongzuo.community.ai.provider.AiResponseMode;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import cumt.zongzuo.community.ai.userprovider.UserAiChatRouter;
import cumt.zongzuo.community.ai.userprovider.PreparedUserAiChat;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.Query;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 把较短或语义抽象的用户问题扩展成一段“假设性答案文档”。
 *
 * <p>该文档不是回答、不是事实，也不会保存到历史或长期记忆。它唯一的
 * 用途是产生一个与长文档表达形式更接近的向量，从而改善“短问题对长文档”
 * 的语义检索。调用方只提供检索文字，不提供整个历史。Agent 的检索文字可能由
 * 私有上下文生成，因此必须沿用冻结路由与每次网络调用前后的运行权/代际检查。</p>
 */
final class HydeHypotheticalDocumentService {

    private static final String PROMPT_TEMPLATE = """
            你是一个只用于检索扩展的 HyDE 生成器。
            请根据用户问题，写一段可能出现在高质量社区长文章中的假设性答案正文。
            只输出正文，不要解释任务，不要输出标题、引用、链接、JSON 或 Markdown 代码块。
            不要声称这段文字已经被验证；其内容只会用来生成检索向量。
            输出不得超过 %d 个 Unicode 码点。

            用户问题：{query}

            假设性答案正文：
            """;

    private final AiCapabilityExecutor executor;
    private final UserAiChatRouter router;
    private final Clock clock;
    private final Duration timeout;
    private final int maxOutputCharacters;

    HydeHypotheticalDocumentService(AiCapabilityExecutor executor,
                                     UserAiChatRouter router,
                                     Clock clock,
                                     Duration timeout,
                                     int maxOutputCharacters) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.router = Objects.requireNonNull(router, "router");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || maxOutputCharacters < 1) {
            throw new IllegalArgumentException("HyDE safety limits are invalid");
        }
        this.maxOutputCharacters = maxOutputCharacters;
    }

    String generate(long userId, String requestId, String normalizedQuery, Instant requestDeadline) {
        return generate(userId, requestId, normalizedQuery, requestDeadline, null, () -> { });
    }

    String generate(long userId, String requestId, String normalizedQuery, Instant requestDeadline,
                    PreparedUserAiChat route, Runnable validate) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(normalizedQuery, "normalizedQuery");
        Objects.requireNonNull(requestDeadline, "requestDeadline");
        Objects.requireNonNull(validate, "validate").run();
        String query = normalizedQuery.strip();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("HyDE query must not be blank");
        }
        Instant deadline = min(requestDeadline, clock.instant().plus(timeout));
        if (!deadline.isAfter(clock.instant())) {
            throw new IllegalStateException("HyDE deadline has expired");
        }
        ChatModel bridge = new GuardedHydeChatModel(executor, router, userId, requestId, deadline,
                route, validate, maxOutputCharacters);
        HyDeTransformer transformer = HyDeTransformer.builder()
                .chatClientBuilder(ChatClient.builder(bridge))
                .promptTemplate(new PromptTemplate(PROMPT_TEMPLATE.formatted(maxOutputCharacters)))
                .build();
        Query source = new Query(query);
        Query transformed = transformer.transform(source);
        validate.run();
        // 官方空响应会返回原 Query；原问题不能被当作成功的假设文档。
        if (transformed == source || transformed.text().equals(source.text())) {
            throw new IllegalStateException("HyDE did not produce a hypothetical document");
        }
        return transformed.text().strip();
    }

    /** 每次请求一个不可变桥接，避免单例或 ThreadLocal 泄漏路由与撤销状态。 */
    private record GuardedHydeChatModel(AiCapabilityExecutor executor, UserAiChatRouter router,
                                        long userId, String requestId, Instant deadline,
                                        PreparedUserAiChat route, Runnable validate,
                                        int maxOutputCharacters) implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<AiPromptMessage> messages = prompt.getInstructions().stream()
                    .map(message -> new AiPromptMessage(switch (message.getMessageType()) {
                        case SYSTEM -> AiPromptRole.SYSTEM;
                        case ASSISTANT -> AiPromptRole.ASSISTANT;
                        case USER -> AiPromptRole.USER;
                        case TOOL -> throw new IllegalArgumentException("HyDE prompt must not contain tools");
                    }, message.getText()))
                    .toList();
            int inputCharacters = messages.stream().mapToInt(message -> message.text().length()).sum();
            AiChatResult generated = executor.execute(new AiInvocationContext(AiCapability.HYDE,
                            userId, requestId + ":hyde", inputCharacters, deadline, false),
                    () -> {
                        validate.run();
                        var command = new AiChatCommand(AiCapability.HYDE, messages, AiResponseMode.TEXT);
                        var routed = route == null ? router.generate(userId, command) : route.generate(command);
                        validate.run();
                        return routed.result();
                    });
            validate.run();
            if (generated.finishReason() == null
                    || !"stop".equalsIgnoreCase(generated.finishReason().strip())) {
                throw new IllegalStateException("HyDE provider did not finish normally");
            }
            String document = generated.text() == null ? "" : generated.text().strip();
            if (document.isEmpty()
                    || document.codePointCount(0, document.length()) > maxOutputCharacters) {
                throw new IllegalStateException("HyDE provider returned an invalid document");
            }
            var metadata = ChatGenerationMetadata.builder()
                    .finishReason(generated.finishReason()).build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(document), metadata)));
        }
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }
}
