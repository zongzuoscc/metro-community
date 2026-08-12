package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatCommand;
import cumt.zongzuo.community.ai.provider.AiPromptMessage;
import cumt.zongzuo.community.ai.provider.AiPromptRole;
import cumt.zongzuo.community.ai.provider.AiResponseMode;
import cumt.zongzuo.community.ai.userprovider.UserAiChatRouter;
import cumt.zongzuo.community.ai.userprovider.UserAiRoutedResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import cumt.zongzuo.community.article.service.PublishedArticleReadService;
import cumt.zongzuo.community.entity.Article;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 承载文章伴读与写作提案两类页面能力。
 *
 * <p>文章总结只从服务端权威发布指针读取，不信任前端传回的正文。
 * 写作能力只生成提案，不注入 mapper 或发布服务，从结构上防止未确认改写。</p>
 */
public final class AgentPageCapabilityService {

    private static final String SUMMARY_SYSTEM = """
            你是社区文章伴读助手。只能根据用户提供的文章事实进行总结，
            先给出一段结论，再列出三到五个核心要点。不得编造作者没有写出的事实。
            """;
    private static final String WRITING_SYSTEM = """
            你是中文写作助手。只返回修改后的文本，不要解释、不要加引号、
            不要使用 Markdown 代码块。必须尊重原意，不得伪造数据、引用或事实。
            """;

    private final PublishedArticleReadService articles;
    private final UserAiChatRouter router;
    private final AiCapabilityExecutor executor;
    private final Clock clock;
    private final Duration summaryTimeout;
    private final Duration writingTimeout;
    private final int summaryMaxCharacters;
    private final int writingMaxCharacters;

    public AgentPageCapabilityService(PublishedArticleReadService articles, UserAiChatRouter router,
                                      AiCapabilityExecutor executor, Clock clock,
                                      Duration summaryTimeout, Duration writingTimeout,
                                      int summaryMaxCharacters, int writingMaxCharacters) {
        this.articles = articles;
        this.router = router;
        this.executor = executor;
        this.clock = clock;
        this.summaryTimeout = positive(summaryTimeout, "summaryTimeout");
        this.writingTimeout = positive(writingTimeout, "writingTimeout");
        this.summaryMaxCharacters = positive(summaryMaxCharacters, "summaryMaxCharacters");
        this.writingMaxCharacters = positive(writingMaxCharacters, "writingMaxCharacters");
    }

    public AgentCapabilityResponse summarizeArticle(long userId, long articleId) {
        return analyzeArticle(userId, articleId, "SUMMARY");
    }

    /**
     * 文章页快捷动作统一由后端读取发布态正文，避免“核心观点/争议点”退化成没有文章上下文的普通问答。
     */
    public AgentCapabilityResponse analyzeArticle(long userId, long articleId, String operation) {
        Article article = articles.findById(articleId);
        if (article == null || article.getId() == null || article.getStatus() == null
                || article.getStatus() != 1 || article.getIsDeleted() == null || article.getIsDeleted() != 0) {
            throw new IllegalArgumentException("文章不存在或已不可见");
        }
        String source = "标题：" + safe(article.getTitle()) + "\n摘要：" + safe(article.getSummary())
                + "\n正文：\n" + safe(article.getContent());
        if (source.length() > summaryMaxCharacters) {
            throw new IllegalArgumentException("文章超出当前可总结的长度限制");
        }
        String instruction = switch (operation == null ? "" : operation.strip().toUpperCase(Locale.ROOT)) {
            case "SUMMARY" -> "请总结全文，先给出结论，再列出三到五个核心要点。";
            case "CORE" -> "请提炼核心观点，并说明文章用于支撑观点的主要理由。";
            case "CONTROVERSY" -> "请找出可能存在争议、前提不足或值得继续讨论的地方。";
            default -> throw new IllegalArgumentException("不支持的文章分析操作");
        };
        AiChatCommand command = new AiChatCommand(AiCapability.ARTICLE_SUMMARY,
                List.of(new AiPromptMessage(AiPromptRole.SYSTEM, SUMMARY_SYSTEM),
                        new AiPromptMessage(AiPromptRole.USER, instruction + "\n\n" + source)), AiResponseMode.TEXT);
        UserAiRoutedResult routed = execute(userId, command, source.length(), summaryTimeout);
        return new AgentCapabilityResponse(routed.result().text(), routed.fundingSource(),
                routed.result().provider(), routed.result().model());
    }

    public WritingSuggestionResponse suggestWriting(long userId, WritingSuggestionRequest request) {
        String operation = request.operation().strip().toUpperCase(Locale.ROOT);
        String instruction = switch (operation) {
            case "POLISH" -> "保持原意，改善语句流畅度、用词准确性和逻辑连贯性。";
            case "SHORTEN" -> "保留必要信息，删除重复和空洞表达，显著缩短文字。";
            case "EXPAND" -> "保持原有观点，补足逻辑过渡与必要解释，但不编造新事实。";
            default -> throw new IllegalArgumentException("不支持的写作操作");
        };
        if (request.selectedText().length() > writingMaxCharacters
                || request.content().length() > writingMaxCharacters) {
            throw new IllegalArgumentException("写作内容超出当前长度限制");
        }
        String input = "操作要求：" + instruction + "\n文章标题：" + safe(request.title())
                + "\n待修改文本：\n" + request.selectedText();
        AiChatCommand command = new AiChatCommand(AiCapability.WRITING,
                List.of(new AiPromptMessage(AiPromptRole.SYSTEM, WRITING_SYSTEM),
                        new AiPromptMessage(AiPromptRole.USER, input)), AiResponseMode.TEXT);
        UserAiRoutedResult routed = execute(userId, command, input.length(), writingTimeout);
        return new WritingSuggestionResponse(operation, request.selectedText(),
                routed.result().text(), request.selectionFrom(), request.selectionTo(),
                request.documentVersion(), routed.fundingSource(), routed.result().provider(),
                routed.result().model());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /** 页面快捷能力与普通 Agent 共用现有配额、bulkhead、重试和 deadline 运行时。 */
    private UserAiRoutedResult execute(long userId, AiChatCommand command, int characters,
                                       Duration timeout) {
        return executor.execute(new AiInvocationContext(command.capability(), userId,
                        "page-" + UUID.randomUUID(), characters, clock.instant().plus(timeout), false),
                () -> router.generate(userId, command));
    }
}
