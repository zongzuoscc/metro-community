package cumt.zongzuo.community.ai.agent.retrieval;

import java.time.Instant;
import java.util.Objects;

public record ArticleRetrievalQuery(long userId, String requestId, String query, Instant deadline) {

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
        Objects.requireNonNull(deadline, "deadline");
    }
}
