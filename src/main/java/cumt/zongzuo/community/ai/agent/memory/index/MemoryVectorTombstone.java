package cumt.zongzuo.community.ai.agent.memory.index;

/** 删除墓碑只携带定位与 CAS 字段，不读取已经删除的记忆正文。 */
public record MemoryVectorTombstone(long memoryVersionId, long userId, long lockVersion) {}
