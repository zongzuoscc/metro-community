package cumt.zongzuo.community.ai.agent.retrieval;

import cumt.zongzuo.community.ai.userprovider.PreparedUserAiChat;
import java.time.Instant;
import java.util.Objects;

public record ArticleRetrievalQuery(long userId, String requestId, String query, Instant deadline,
                                    PreparedUserAiChat route, Runnable validate, String originalQuestion) {

    /** 原问题与实际检索词分开保存，避免 ReAct 改写成关键词后丢失描述型意图。 */
    public ArticleRetrievalQuery(long userId, String requestId, String query, Instant deadline,
                               PreparedUserAiChat route, Runnable validate) {
        this(userId, requestId, query, deadline, route, validate, query);
    }

    /** 非 Agent 调用方兼容入口；有运行租约的调用方必须提供冻结路由与验证器。 */
    public ArticleRetrievalQuery(long userId, String requestId, String query, Instant deadline) {
        this(userId, requestId, query, deadline, null, () -> { });
    }

    public ArticleRetrievalQuery {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        query = query.strip();
        if (originalQuestion == null || originalQuestion.isBlank()) {
            throw new IllegalArgumentException("原始问题不能为空");
        }
        originalQuestion = originalQuestion.strip();
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(validate, "validate");
    }
}
