package cumt.zongzuo.community.websocket;

import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebSocketSessionRegistryTest {

    @Test
    void closingOldSessionDoesNotRemoveItsReplacement() {
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
        Session oldSession = mock(Session.class);
        Session replacement = mock(Session.class);

        registry.replace(7L, oldSession);
        assertThat(registry.replace(7L, replacement)).isSameAs(oldSession);

        assertThat(registry.remove(7L, oldSession)).isFalse();
        assertThat(registry.find(7L)).isSameAs(replacement);
        assertThat(registry.remove(7L, replacement)).isTrue();
        assertThat(registry.find(7L)).isNull();
    }
}
