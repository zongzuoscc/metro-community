package cumt.zongzuo.community.ai.agent.turn;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

/** 通知只负责唤醒：不携带正文，也不允许一个浏览器把另一个浏览器的通知消费掉。 */
class AgentTurnEventSignalsTest {
    @Test void wakesAllReadersOfOnlyTheChangedTurn() throws Exception {
        var signals = new AgentTurnEventSignals();
        try (var first = signals.subscribe(1); var second = signals.subscribe(1);
             var unrelated = signals.subscribe(2)) {
            signals.signal(1);
            assertThat(first.await(Duration.ZERO)).isTrue();
            assertThat(second.await(Duration.ZERO)).isTrue();
            assertThat(unrelated.await(Duration.ZERO)).isFalse();
        }
        assertThat(signals.subscriberCount()).isZero();
    }

    @Test void retainsWakeupThatArrivesBeforeWaitAndCoalescesBursts() throws Exception {
        var signals = new AgentTurnEventSignals();
        try (var subscription = signals.subscribe(1)) {
            for (int i = 0; i < 10_000; i++) signals.signal(1);
            assertThat(subscription.await(Duration.ZERO)).isTrue();
            assertThat(subscription.await(Duration.ZERO)).isFalse();
        }
    }

    @Test void closeIsIdempotentAndDoesNotLeakSubscriptions() {
        var signals = new AgentTurnEventSignals();
        var subscription = signals.subscribe(1);
        subscription.close();
        subscription.close();
        signals.signal(1);
        assertThat(signals.subscriberCount()).isZero();
    }
}
