package cumt.zongzuo.community.ai.provider;

/** 模型传输边界：只接收 content 增量，检查运行权时不得输出推理过程或工具参数。 */
public interface AiStreamObserver {
    void onDelta(String text);

    /** 无正文到达时传输层也会定期调用，使取消不必等待下一个 token。 */
    default void checkActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Stream interrupted");
        }
    }
}
