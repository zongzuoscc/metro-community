package cumt.zongzuo.community.ai.agent.retrieval;

import java.util.List;

public record ResolvedArticleChunk(long chunkId, long articleId, long revisionId, int chunkNo,
                                   String title, List<String> headingPath, String bodyText,
                                   String revisionContentHash, String chunkHash) {

    public ResolvedArticleChunk {
        headingPath = List.copyOf(headingPath);
    }

    public String sourceId() {
        return "A" + articleId + ":R" + revisionId + ":C" + chunkId;
    }
}
