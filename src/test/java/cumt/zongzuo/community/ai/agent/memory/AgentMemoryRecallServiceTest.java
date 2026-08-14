package cumt.zongzuo.community.ai.agent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentMemoryRecallServiceTest {

    @Test
    void semanticRankingCanRecallAParaphraseThatHasNoLexicalOverlap() {
        AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
        AgentMemorySemanticRanker semantic = mock(AgentMemorySemanticRanker.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentMemorySemanticRanker> provider = mock(ObjectProvider.class);
        AgentMemoryView concise = memory(31L, "用户偏好简短直接的回答");
        AgentMemoryView travel = memory(32L, "用户计划去杭州旅游");
        when(mapper.enabled(9L)).thenReturn(true);
        when(mapper.listActive(9L, 100)).thenReturn(List.of(concise, travel));
        when(provider.getIfAvailable()).thenReturn(semantic);
        when(semantic.rank(9L, "请言简意赅地回答", List.of(concise, travel)))
                .thenReturn(List.of(new AgentMemorySemanticScore(31L, .98D),
                        new AgentMemorySemanticScore(32L, .12D)));

        List<AgentMemoryView> recalled = new AgentMemoryRecallService(mapper, provider)
                .recall(9L, "请言简意赅地回答", 1);

        assertThat(recalled).containsExactly(concise);
    }

    @Test
    void unavailableSemanticRankingFallsBackToExistingLexicalRecall() {
        AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
        AgentMemorySemanticRanker semantic = mock(AgentMemorySemanticRanker.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentMemorySemanticRanker> provider = mock(ObjectProvider.class);
        AgentMemoryView java = memory(41L, "用户正在准备 Java 后端面试");
        AgentMemoryView travel = memory(42L, "用户计划去杭州旅游");
        when(mapper.enabled(10L)).thenReturn(true);
        when(mapper.listActive(10L, 100)).thenReturn(List.of(travel, java));
        when(provider.getIfAvailable()).thenReturn(semantic);
        when(semantic.rank(10L, "Java 面试", List.of(travel, java)))
                .thenThrow(new IllegalStateException("embedding unavailable"));

        List<AgentMemoryView> recalled = new AgentMemoryRecallService(mapper, provider)
                .recall(10L, "Java 面试", 1);

        assertThat(recalled).containsExactly(java);
    }

    private static AgentMemoryView memory(long id, String content) {
        return new AgentMemoryView(id, "GOAL", content, 1L,
                "ACTIVE", null, "MANUAL");
    }
}
