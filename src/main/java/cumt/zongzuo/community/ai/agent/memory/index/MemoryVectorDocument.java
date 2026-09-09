package cumt.zongzuo.community.ai.agent.memory.index;

/** 向量中只存版本标识与过滤字段，正文始终从 MySQL 校验后读取。 */
public record MemoryVectorDocument(MemoryProjectionRow source, String embeddingModel,
                                   float[] embedding) {
    public MemoryVectorDocument { embedding = embedding.clone(); }
    @Override public float[] embedding() { return embedding.clone(); }
}
