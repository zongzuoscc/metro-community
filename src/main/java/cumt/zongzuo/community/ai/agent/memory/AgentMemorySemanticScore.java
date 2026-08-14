package cumt.zongzuo.community.ai.agent.memory;

/** 一条记忆与当前问题的余弦相似度；分值越大，语义越接近。 */
public record AgentMemorySemanticScore(long memoryId, double score) {
}
