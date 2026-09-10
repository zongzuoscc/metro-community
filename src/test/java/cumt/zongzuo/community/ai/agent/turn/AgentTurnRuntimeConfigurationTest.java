package cumt.zongzuo.community.ai.agent.turn;

import cumt.zongzuo.community.ai.config.MetroAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class AgentTurnRuntimeConfigurationTest {
    @Test void invalidBudgetsFailAtStartupInsteadOfSilentlyBecomingUnbounded() {
        var settings = new AgentTurnRuntimeProperties();
        settings.setWorkers(9);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings.effectiveWorkers(8))
                .isInstanceOf(IllegalArgumentException.class);
        settings.setWorkers(0);
        settings.setQueueCapacity(-1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings.effectiveWorkers(8))
                .isInstanceOf(IllegalArgumentException.class);
        settings.setQueueCapacity(0);
        settings.setSseConcurrency(-1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings.effectiveWorkers(8))
                .isInstanceOf(IllegalArgumentException.class);
        settings.setSseConcurrency(1);
        settings.setSseTimeout(java.time.Duration.ZERO);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings.effectiveWorkers(8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelCapacityIsNotSilentlySerializedThroughTwoTurnWorkers() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentTurnRuntimeConfiguration.class)
                .withBean(MetroAiProperties.class, () -> {
                    MetroAiProperties properties = new MetroAiProperties();
                    properties.getAgent().setBulkhead(8);
                    return properties;
                })
                .withPropertyValues("metro.ai.enabled=true", "metro.ai.agent.enabled=true")
                .run(context -> {
                    ExecutorService executor = context.getBean("agentTurnExecutor", ExecutorService.class);
                    CountDownLatch started = new CountDownLatch(8), release = new CountDownLatch(1);
                    try {
                        for (int i = 0; i < 8; i++) executor.execute(() -> {
                            started.countDown();
                            try { release.await(); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        });
                        // 模型层已经允许八路；外层不应把另外六路无谓地堵在固定二线程队列中。
                        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                    } finally { release.countDown(); executor.shutdownNow(); }
                });
    }
}
