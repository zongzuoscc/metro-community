package cumt.zongzuo.community.ai.agent.retrieval;

public record RankedArticleChunk(ResolvedArticleChunk chunk, double rrfScore,
                                 Integer lexicalRank, Integer denseRank) {

    public long chunkId() {
        return chunk.chunkId();
    }
}
