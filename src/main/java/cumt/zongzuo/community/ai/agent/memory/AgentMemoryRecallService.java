package cumt.zongzuo.community.ai.agent.memory;

import cumt.zongzuo.community.ai.agent.memory.index.AgentMemoryVectorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 长期记忆读取门面：语义召回只走持久 Milvus 索引，管理查询仍以 MySQL 为事实源。
 * 不在每次请求中重建临时向量，也不在向量服务缺失或异常时偷偷切换词法排序。
 */
@Service
public class AgentMemoryRecallService {
    private final AgentMemoryMapper mapper;
    private final ObjectProvider<AgentMemoryVectorService> vectors;

    public AgentMemoryRecallService(AgentMemoryMapper mapper,
                                    ObjectProvider<AgentMemoryVectorService> vectors) {
        this.mapper = mapper;
        this.vectors = vectors;
    }

    /** 非 Agent 调用方仍有明确的 45 秒预算，不能无限等待外部 embedding 或 Milvus。 */
    public List<AgentMemoryView> recall(long userId, String query, int limit) {
        return recall(userId, query, limit, Instant.now().plusSeconds(45));
    }

    /**
     * 用户关闭记忆时立即短路；启用时必须具备持久向量服务，且所有失败原样上抛。
     * deadline 由整轮 Agent 共享，召回不能重新起算预算或吞掉超时后继续生成回答。
     */
    public List<AgentMemoryView> recall(long userId, String query, int limit, Instant deadline) {
        if (limit < 1 || limit > 16) throw new IllegalArgumentException("Invalid memory limit");
        Objects.requireNonNull(deadline, "deadline");
        mapper.ensureSetting(userId);
        if (!Boolean.TRUE.equals(mapper.enabled(userId))) return List.of();
        AgentMemoryVectorService service = vectors.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("Persistent memory vector service is unavailable");
        }
        return service.recall(userId, query, limit, deadline);
    }

    public List<AgentMemoryView> list(long userId) {
        mapper.ensureSetting(userId);
        return mapper.listActive(userId, 100);
    }

    public AgentMemoryView find(long userId, long memoryId) {
        return mapper.find(memoryId, userId);
    }

    /** 尚未创建主对话时没有记忆代际，按零处理；创建/修改记忆会推进代际。 */
    public long epoch(long userId) {
        Long value=mapper.memoryEpoch(userId);
        return value==null?0:value;
    }
}
