package cumt.zongzuo.community.ai.agent.memory;

import cumt.zongzuo.community.ai.agent.memory.index.AgentMemoryVectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentMemoryRecallServiceTest {
    private final AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
    private final AgentMemoryVectorService vectors = mock(AgentMemoryVectorService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentMemoryVectorService> provider = mock(ObjectProvider.class);
    private final AgentMemoryRecallService service = new AgentMemoryRecallService(mapper, provider);

    /** 用户开启记忆时，缺少持久向量服务不能伪装成正常的空召回或词法结果。 */
    @Test
    void enabledMemoryRequiresPersistentVectorService() {
        when(mapper.enabled(9L)).thenReturn(true);

        assertThatThrownBy(() -> service.recall(9L, "回答风格", 8))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("vector");
        verify(mapper, never()).listActive(anyLong(), anyInt());
    }

    @Test
    void semanticRecallPreservesVectorOrderingAndTheWholeTurnDeadline() {
        var deadline = Instant.now().plusSeconds(18);
        var concise = memory(31L, "用户偏好简短直接的回答");
        var technical = memory(32L, "用户希望先得到可执行步骤");
        when(mapper.enabled(9L)).thenReturn(true);
        when(provider.getIfAvailable()).thenReturn(vectors);
        when(vectors.recall(9L, "请言简意赅地回答", 2, deadline))
                .thenReturn(List.of(concise, technical));

        assertThat(service.recall(9L, "请言简意赅地回答", 2, deadline))
                .containsExactly(concise, technical);
        verify(mapper, never()).listActive(anyLong(), anyInt());
    }

    @Test
    void disabledMemoryDoesNotTouchVectorInfrastructure() {
        when(mapper.enabled(9L)).thenReturn(false);

        assertThat(service.recall(9L, "回答风格", 8)).isEmpty();
        verifyNoInteractions(provider, vectors);
        verify(mapper, never()).listActive(anyLong(), anyInt());
    }

    @Test
    void vectorFailureIsPropagatedWithoutLexicalFallback() {
        var deadline = Instant.now().plusSeconds(10);
        var failure = new IllegalStateException("Milvus unavailable");
        when(mapper.enabled(10L)).thenReturn(true);
        when(provider.getIfAvailable()).thenReturn(vectors);
        when(vectors.recall(10L, "Java 面试", 1, deadline)).thenThrow(failure);

        assertThatThrownBy(() -> service.recall(10L, "Java 面试", 1, deadline)).isSameAs(failure);
        verify(mapper, never()).listActive(anyLong(), anyInt());
    }

    @Test
    void defaultRecallBudgetIsFortyFiveSeconds() {
        when(mapper.enabled(9L)).thenReturn(true);
        when(provider.getIfAvailable()).thenReturn(vectors);
        var before = Instant.now().plusSeconds(45);
        when(vectors.recall(eq(9L), eq("偏好"), eq(1), any())).thenReturn(List.of());

        assertThat(service.recall(9L, "偏好", 1)).isEmpty();
        var after = Instant.now().plusSeconds(45);
        var deadline = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(vectors).recall(eq(9L), eq("偏好"), eq(1), deadline.capture());
        assertThat(deadline.getValue()).isBetween(before, after);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 17})
    void invalidRecallLimitsFailBeforeReadingPersonalData(int limit) {
        assertThatThrownBy(() -> service.recall(9L, "偏好", limit))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper, provider);
    }

    @Test
    void managementReadsStayAvailableWithoutAVectorService() {
        var preference = memory(31L, "用户偏好简短直接的回答");
        when(mapper.listActive(9L, 100)).thenReturn(List.of(preference));
        when(mapper.find(31L, 9L)).thenReturn(preference);

        assertThat(service.list(9L)).containsExactly(preference);
        assertThat(service.find(9L, 31L)).isEqualTo(preference);
        verifyNoInteractions(provider, vectors);
    }

    private static AgentMemoryView memory(long id, String content) {
        return new AgentMemoryView(id, "PREFERENCE", content, 1L, "ACTIVE", null, "MANUAL");
    }
}
