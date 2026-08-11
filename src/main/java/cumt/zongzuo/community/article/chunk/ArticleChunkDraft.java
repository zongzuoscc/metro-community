package cumt.zongzuo.community.article.chunk;

import java.util.List;

public record ArticleChunkDraft(long id,
                                long revisionId,
                                int chunkNo,
                                long parserGeneration,
                                String parserVersion,
                                String title,
                                List<String> headingPath,
                                String bodyText,
                                int startCodepoint,
                                int endCodepoint,
                                int estimatedTokens,
                                String chunkHash,
                                String embeddingInputHash) {

    public ArticleChunkDraft {
        headingPath = List.copyOf(headingPath);
    }
}
