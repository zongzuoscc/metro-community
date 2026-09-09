package cumt.zongzuo.community.ai.agent.context;

import cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit;
import cumt.zongzuo.community.ai.userprovider.PreparedUserAiChat;
import java.time.Instant;
import java.util.List;

/** 模型只提出摘要和带原文证据的事实，数据库写入权始终由后端掌握。 */
public interface AgentCompactionModel {
    /** 本地预算检查，必须与 extract 使用同一份完整序列化提示词；不会调用模型。 */
    boolean fits(Request request);
    Extraction extract(Request request);
    record Request(long userId, String requestId, String previousSummary,
                   List<AgentConversationHistoryHit> messages, boolean extractMemories,
                   PreparedUserAiChat route, Instant deadline) { }
    record Memory(String category, String content, long sourceMessageId, String quote) { }
    record Extraction(String summary, List<Memory> memories) {
        public Extraction { memories = List.copyOf(memories); }
    }
}
