package cumt.zongzuo.community.ai.agent.retrieval;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatCommand;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.ai.provider.AiPromptMessage;
import cumt.zongzuo.community.ai.provider.AiPromptRole;
import cumt.zongzuo.community.ai.provider.AiResponseMode;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import cumt.zongzuo.community.ai.userprovider.UserAiChatRouter;

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
 * 的语义检索。为了防止个人数据或已检索文章污染查询，本服务只接收当前
 * 规范化问题，调用方无法传入记忆、历史或文章正文。</p>
 */
final class HydeHypotheticalDocumentService {

    private static final String SYSTEM_PROMPT = """
            你是一个只用于检索扩展的 HyDE 生成器。
            请根据用户问题，写一段可能出现在高质量社区长文章中的假设性答案正文。
            只输出正文，不要解释任务，不要输出标题、引用、链接、JSON 或 Markdown 代码块。
            不要声称这段文字已经被验证；其内容只会用来生成检索向量。
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
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(normalizedQuery, "normalizedQuery");
        Objects.requireNonNull(requestDeadline, "requestDeadline");
        String query = normalizedQuery.strip();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("HyDE query must not be blank");
        }
        // 限额同时写入提示词和结果校验：前者减少无效 Token，后者防止不遵守指令的模型绕过上限。
        String boundedSystemPrompt = SYSTEM_PROMPT + "\n输出不得超过 "
                + maxOutputCharacters + " 个中文字符或 Unicode 码点。";
        List<AiPromptMessage> prompt = List.of(
                new AiPromptMessage(AiPromptRole.SYSTEM, boundedSystemPrompt),
                new AiPromptMessage(AiPromptRole.USER, query));
        int inputCharacters = prompt.stream().mapToInt(message -> message.text().length()).sum();
        Instant deadline = min(requestDeadline, clock.instant().plus(timeout));
        if (!deadline.isAfter(clock.instant())) {
            throw new IllegalStateException("HyDE deadline has expired");
        }

        AiChatResult generated = executor.execute(new AiInvocationContext(AiCapability.HYDE, userId,
                        requestId + ":hyde", inputCharacters, deadline, false),
                () -> router.generate(userId, new AiChatCommand(AiCapability.HYDE, prompt,
                        AiResponseMode.TEXT)).result());
        // 被过滤、截断或其它非正常终止的文本不是可用的假设文档，不应浪费第二次向量调用。
        if (generated.finishReason() == null
                || !"stop".equalsIgnoreCase(generated.finishReason().strip())) {
            throw new IllegalStateException("HyDE provider did not finish normally");
        }
        String document = generated.text() == null ? "" : generated.text().strip();
        int outputCharacters = document.codePointCount(0, document.length());
        if (document.isEmpty() || outputCharacters > maxOutputCharacters) {
            throw new IllegalStateException("HyDE provider returned an invalid document");
        }
        return document;
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }
}
