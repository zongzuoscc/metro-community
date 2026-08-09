package cumt.zongzuo.community.ai.runtime;

@FunctionalInterface
public interface AiQuotaService {

    void acquire(AiInvocationContext context);
}
