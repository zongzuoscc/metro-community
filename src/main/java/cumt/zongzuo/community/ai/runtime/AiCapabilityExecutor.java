package cumt.zongzuo.community.ai.runtime;

import io.github.resilience4j.core.functions.CheckedSupplier;

public interface AiCapabilityExecutor {

    <T> T execute(AiInvocationContext context, CheckedSupplier<T> operation);
}
