package cumt.zongzuo.community.ai.agent;

public record AgentCitation(int marker, String sourceId, long articleId, long revisionId,
                            long chunkId, String title, String quote, String url) {
}
