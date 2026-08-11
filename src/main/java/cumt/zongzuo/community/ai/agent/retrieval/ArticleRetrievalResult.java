package cumt.zongzuo.community.ai.agent.retrieval;

import java.util.List;

public record ArticleRetrievalResult(int lexicalCount, int denseCount, boolean lexicalAvailable,
                                     boolean denseAvailable, List<ResolvedArticleChunk> authorizedChunks,
                                     List<RankedArticleChunk> rankedCandidates) {

    public ArticleRetrievalResult {
        authorizedChunks = List.copyOf(authorizedChunks);
        rankedCandidates = List.copyOf(rankedCandidates);
    }
}
