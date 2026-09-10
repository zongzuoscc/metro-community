package cumt.zongzuo.community.ai.agent.turn;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnStore;
import cumt.zongzuo.community.ai.web.AiApiException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用真实事件存储服务验证连接级授权边界；只模拟数据库、Redis 等外部依赖。 */
class AgentTurnReplayReaderTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    private final AgentTurnMapper mapper = mock(AgentTurnMapper.class);
    private final TemporaryTurnStore temporary = mock(TemporaryTurnStore.class);
    private final AgentTurnEventStore events = new AgentTurnEventStore(
            redis, mapper, temporary, new ObjectMapper(), new AgentTurnEventSignals());

    @Test void persistentReadsAuthorizeOnceButStateChecksStillUseDatabase() {
        var turn = new AgentTurnRecord();
        turn.setState("RUNNING");
        when(mapper.selectById(1L, 9L)).thenReturn(turn);
        var reader = events.openReplay(1L, 9L);
        for (int i = 0; i < 100; i++) assertThat(reader.read(null, 100)).isEmpty();
        verify(mapper, times(1)).selectById(1L, 9L);
        assertThat(reader.isTerminal()).isFalse();
        verify(mapper, times(2)).selectById(1L, 9L);
    }

    @Test void cannotAcquireAnotherUsersReplayHandle() {
        assertThatThrownBy(() -> events.openReplay(1L, 10L)).isInstanceOf(AiApiException.class);
        verifyNoInteractions(redis);
    }

    @Test void deletedTemporarySessionCannotReplayEvenWithAnExistingHandle() {
        var turn = mock(cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnRecord.class);
        when(temporary.find(-1L, 9L)).thenReturn(turn, null);
        var reader = events.openReplay(-1L, 9L);
        assertThatThrownBy(() -> reader.read(null, 100)).isInstanceOf(AiApiException.class);
        verifyNoInteractions(redis, mapper);
    }
}
