package cumt.zongzuo.community.ai.agent.history;

import cumt.zongzuo.community.ai.agent.memory.AgentMemorySafetyPolicy;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRecentConversationTest {
    private final AgentConversationHistoryMapper mapper = mock(AgentConversationHistoryMapper.class);
    private final AgentConversationHistorySearchService service =
            new AgentConversationHistorySearchService(mapper, new AgentMemorySafetyPolicy());

    @Test
    void keepsLongTechnicalAnswersWithoutApplyingTheProfileMemoryLengthLimit() {
        var question = row(1, 1, 9, "USER", "解释 Token 预算和 API Key 配置");
        var answer = row(2, 1, 9, "ASSISTANT", "解释技术方案。".repeat(400));
        when(mapper.recentCompletedMessages(9, 24)).thenReturn(List.of(answer, question));
        assertThat(service.recentConversation(9, 24)).containsExactly(question, answer);
    }

    @Test
    void rejectsForeignIncompleteAndCredentialBearingTurns() {
        when(mapper.recentCompletedMessages(9, 24)).thenReturn(List.of(
                row(1, 1, 9, "USER", "我的 API key 是 secret-history-123"),
                row(2, 1, 9, "ASSISTANT", "不应带入这轮"),
                row(3, 2, 10, "USER", "别人问题"), row(4, 2, 10, "ASSISTANT", "别人回答"),
                row(5, 3, 9, "USER", "孤立问题"),
                row(6, 4, 9, "USER", "有效问题"), row(7, 4, 9, "ASSISTANT", "有效回答")));
        assertThat(service.recentConversation(9, 24)).extracting(AgentConversationHistoryHit::messageId)
                .containsExactly(6L, 7L);
    }

    @Test
    void summariesCannotReintroduceCredentialsFilteredFromOriginalMessages() {
        when(mapper.recentSummaries(9, 3)).thenReturn(List.of(
                new AgentEpisodeSummaryView(2, 2, "API key 是 secret-history-123", null),
                new AgentEpisodeSummaryView(1, 1, "讨论了事务和索引", null)));
        assertThat(service.recentSummaries(9, 3)).extracting(AgentEpisodeSummaryView::summary)
                .containsExactly("讨论了事务和索引");
    }

    private static AgentConversationHistoryHit row(long id, long turn, long user, String role, String content) {
        return new AgentConversationHistoryHit(id, turn, user, role, content,
                LocalDateTime.parse("2026-09-08T00:00:00"));
    }

    @Test
    void pageCursorIsComputedBeforeCredentialFilteringAndUsesTurnNotMessageId() {
        when(mapper.completedMessagesBefore(9, 101, 2)).thenReturn(List.of(
                row(500, 99, 9, "USER", "API key 是 secret-history-123"),
                row(501, 99, 9, "ASSISTANT", "已收到"),
                row(502, 100, 9, "USER", "password=secret-password"),
                row(503, 100, 9, "ASSISTANT", "已收到")));
        var page = service.conversationPage(9, 101, 2);
        assertThat(page.messages()).isEmpty();
        assertThat(page.nextBeforeTurnId()).isEqualTo(99);
        assertThat(page.exhausted()).isFalse();
    }
}
