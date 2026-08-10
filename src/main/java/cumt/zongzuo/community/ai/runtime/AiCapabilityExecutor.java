package cumt.zongzuo.community.ai.runtime;

import io.github.resilience4j.core.functions.CheckedSupplier;

public interface AiCapabilityExecutor {

    <T> T execute(AiInvocationContext context, CheckedSupplier<T> operation);

    <A, T> T execute(AiInvocationContext context, AttemptObserver<A, T> observer,
                     AttemptOperation<A, T> operation);

    interface AttemptObserver<A, T> {

        A begin();

        void complete(A attempt, T result, Throwable error);
    }

    @FunctionalInterface
    interface AttemptOperation<A, T> {

        T execute(A attempt) throws Throwable;
    }
}
