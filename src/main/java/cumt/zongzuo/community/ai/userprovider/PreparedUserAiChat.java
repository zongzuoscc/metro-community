package cumt.zongzuo.community.ai.userprovider;

import cumt.zongzuo.community.ai.provider.AiChatCommand;
import java.util.function.Function;
import cumt.zongzuo.community.ai.provider.AiStreamObserver;

/** 一次回答的路由快照。先按该模型计算预算，再用同一份配置发送，避免设置变更造成错配。 */
public record PreparedUserAiChat(String model, UserAiFundingSource fundingSource,
                                Function<AiChatCommand, UserAiRoutedResult> invocation,
                                Runnable guard, StreamInvocation streaming) {
    @FunctionalInterface
    public interface StreamInvocation {
        UserAiRoutedResult invoke(AiChatCommand command, AiStreamObserver observer);
    }

    public PreparedUserAiChat(String model, UserAiFundingSource fundingSource,
                              Function<AiChatCommand, UserAiRoutedResult> invocation, Runnable guard) {
        this(model, fundingSource, invocation, guard, (command, observer) -> {
            throw new UnsupportedOperationException("Frozen route does not support streaming");
        });
    }
    /** 普通路由快照保留原有构造方式；Agent 可额外绑定运行权及记忆版本检查。 */
    public PreparedUserAiChat(String model, UserAiFundingSource fundingSource,
                              Function<AiChatCommand, UserAiRoutedResult> invocation) {
        this(model, fundingSource, invocation, () -> {});
    }

    public PreparedUserAiChat {
        java.util.Objects.requireNonNull(invocation, "invocation");
        java.util.Objects.requireNonNull(guard, "guard");
    }

    /** 检索内的 Embedding 也能复用此检查，而不必发起一次聊天模型调用。 */
    public void validate() { guard.run(); }

    public UserAiRoutedResult generate(AiChatCommand command) {
        validate();
        UserAiRoutedResult result = invocation.apply(command);
        validate();
        return result;
    }

    /** 流开始、增量处理及无数据等待期间都检查同一份路由的运行权。 */
    public UserAiRoutedResult stream(AiChatCommand command, AiStreamObserver observer) {
        validate();
        AiStreamObserver guarded = new AiStreamObserver() {
            private long nextCheck;
            public void onDelta(String text) { checkActive(); observer.onDelta(text); }
            public synchronized void checkActive() {
                observer.checkActive();
                long now = System.nanoTime();
                // 心跳校验会访问存储，按秒节流，不能每个 token 查询数据库。
                if (now >= nextCheck) { validate(); nextCheck = now + 1_000_000_000L; }
            }
        };
        UserAiRoutedResult result = streaming.invoke(command, guarded);
        validate();
        return result;
    }
}
