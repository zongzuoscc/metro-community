package cumt.zongzuo.community.ai.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.turn.*;
import cumt.zongzuo.community.ai.web.AiApiException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证实际 SSE 写循环：补读积压、终态退出、异步启动前的临时会话失效。 */
class AgentTurnSseWakeupTest {
    private final AgentTurnEventStore.ReplayReader reader = mock(AgentTurnEventStore.ReplayReader.class);
    private final AgentTurnController controller = new AgentTurnController(null, null, null, null,
            null, null, null, null, new ObjectMapper().findAndRegisterModules(), null, null,
            new AgentTurnRuntimeProperties());

    @Test void drainsMoreThanOnePageWithoutWaitingForAnotherNotification() {
        var subscription = new AgentTurnEventSignals().subscribe(1);
        when(reader.subscribe()).thenReturn(subscription);
        var page = IntStream.rangeClosed(1, 100).mapToObj(i -> event(i, "delta")).toList();
        when(reader.read(null, 100)).thenReturn(page);
        when(reader.read("100-0", 100)).thenReturn(List.of(event(101, "done")));
        var output = new ByteArrayOutputStream();
        ReflectionTestUtils.invokeMethod(controller, "streamEvents", output, reader, null, List.of());
        assertThat(output.toString()).contains("id: 1-0", "id: 100-0", "event: done");
        verify(reader, times(1)).isTerminal();
    }

    @Test void temporarySessionDeletedAfterHttpPrecheckNeverWritesInitialPayload() {
        doThrow(AiApiException.temporarySessionExpired()).when(reader).validateTemporarySession();
        var output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(controller, "streamEvents",
                output, reader, null, List.of(event(1, "done")))).isInstanceOf(AiApiException.class);
        assertThat(output.size()).isZero();
        verify(reader, never()).subscribe();
    }

    private static AgentTurnEvent event(int id, String type) {
        return new AgentTurnEvent(id + "-0", 1, 1, type, Instant.EPOCH, Map.of("text", "测试正文"));
    }
}
