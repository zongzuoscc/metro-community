package cumt.zongzuo.community.ai.agent.retrieval;

public record RankedArticleChunk(ResolvedArticleChunk chunk, double rrfScore,
                                 Integer lexicalRank, Integer denseRank, Integer hydeRank) {

    /** 保留原有双路检索的构造方式，方便未启用 HyDE 的调用方明确表达无第三路排名。 */
    public RankedArticleChunk(ResolvedArticleChunk chunk, double rrfScore,
                              Integer lexicalRank, Integer denseRank) {
        this(chunk, rrfScore, lexicalRank, denseRank, null);
    }

    public long chunkId() {
        return chunk.chunkId();
    }
}
