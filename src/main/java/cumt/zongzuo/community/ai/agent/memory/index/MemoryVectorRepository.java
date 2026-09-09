package cumt.zongzuo.community.ai.agent.memory.index;

import java.time.Instant;
import java.util.List;

/** 实现必须在成功返回前完成 STRONG 可见性校验，且所有网络操作受 deadline 约束。 */
public interface MemoryVectorRepository {
    void upsertAndVerify(List<MemoryVectorDocument> documents, Instant deadline);
    void deleteAndVerify(long userId, List<Long> versionIds, Instant deadline);
    List<Long> search(long userId, String model, float[] query, int limit, Instant deadline);
}
