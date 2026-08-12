package cumt.zongzuo.community.ai.agent.memory;

public record AgentMemoryView(long id, String category, String content, long version,
                              String state) {
}
