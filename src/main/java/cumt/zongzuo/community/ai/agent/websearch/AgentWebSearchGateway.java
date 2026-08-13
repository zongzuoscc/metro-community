package cumt.zongzuo.community.ai.agent.websearch;

import java.time.Instant;

/** 将外部联网检索隔离在 Agent 领域之外，便于后续替换百炼 MCP 或其他搜索服务。 */
public interface AgentWebSearchGateway {

    AgentWebSearchResult search(String query, Instant deadline);
}
