package cumt.zongzuo.community.ai.agent.turn;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 模型增量到 Redis 事件的短缓冲。首段立即发出；后续每次增量到达时，若距上次推送
 * 已满 200ms 或累积满 96 个字符便推送，降低数据库栅栏检查及 Redis 写入频率。
 * 这不是定时器：模型暂停时余量等待下一段或生成结束，但不会先等整个答案再切片。
 * Redis 写入成功才允许 SSE 重放。新执行编号先发重置事件，防止故障接管后拼接旧答案。
 */
public final class AgentAnswerStream implements Consumer<String> {
    private final AgentTurnEventStore events;
    private final long turnId, userId, fence;
    private final UUID runId;
    private final StringBuilder pending = new StringBuilder();
    private long lastFlush;
    private boolean sent;

    public AgentAnswerStream(AgentTurnEventStore events, long turnId, long userId, UUID runId, long fence) {
        this.events = events; this.turnId = turnId; this.userId = userId; this.runId = runId; this.fence = fence;
        events.append(turnId, userId, runId, fence, "answer_start",
                Map.of("runId", runId.toString(), "provisional", true));
    }

    @Override public synchronized void accept(String text) {
        pending.append(text);
        if (!sent || pending.length() >= 96 || System.nanoTime() - lastFlush >= 200_000_000L) flush();
    }

    /** 只在生成结束的收尾阶段补发尚未凑满一批的尾部，绝不重新切分完整答案。 */
    public synchronized void flush() {
        if (pending.isEmpty()) return;
        events.append(turnId, userId, runId, fence, "delta",
                Map.of("textAppend", pending.toString(), "runId", runId.toString(), "provisional", true));
        pending.setLength(0); sent = true; lastFlush = System.nanoTime();
    }
}
