package cumt.zongzuo.community.ai.userprovider;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 冻结路由的运行检查必须覆盖真正发送前和返回后，而不只在入队前检查。 */
class PreparedUserAiChatTest {
    @Test
    void rejectedGuardPreventsNetworkInvocation() {
        var invoked = new AtomicBoolean();
        var route = new PreparedUserAiChat("test", UserAiFundingSource.PLATFORM,
                command -> { invoked.set(true); return null; },
                () -> { throw new CancellationException("cancelled"); });
        assertThatThrownBy(() -> route.generate(null)).isInstanceOf(CancellationException.class);
        assertThat(invoked).isFalse();
    }

    @Test
    void resultIsDiscardedWhenGuardChangesDuringInvocation() {
        var checks = new AtomicInteger();
        var route = new PreparedUserAiChat("test", UserAiFundingSource.PLATFORM,
                command -> null,
                () -> { if (checks.incrementAndGet() == 2) throw new CancellationException("changed"); });
        assertThatThrownBy(() -> route.generate(null)).isInstanceOf(CancellationException.class);
        assertThat(checks).hasValue(2);
    }

    @Test
    void retrievalCanValidateWithoutCallingChatModel() {
        var checks = new AtomicInteger();
        var invoked = new AtomicBoolean();
        var route = new PreparedUserAiChat("test", UserAiFundingSource.PLATFORM,
                command -> { invoked.set(true); return null; }, checks::incrementAndGet);
        route.validate();
        assertThat(checks).hasValue(1);
        assertThat(invoked).isFalse();
    }
}
