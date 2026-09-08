package cumt.zongzuo.community.ai.agent.context;

import cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit;
import cumt.zongzuo.community.ai.agent.history.AgentConversationPage;
import cumt.zongzuo.community.ai.agent.retrieval.ResolvedArticleChunk;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSearchResult;
import cumt.zongzuo.community.ai.runtime.AiExecutionException;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AgentContextAssemblerTest {
    private final AgentPromptBudget budget = new AgentPromptBudget(new AgentContextProperties());
    private final AgentContextAssembler assembler = new AgentContextAssembler(budget, 400_000);

    @Test
    void deduplicatesRecentAndRetrievedHistoryByMessageId() {
        var user = row(1, 1, "USER", "三个方案");
        var assistant = row(2, 1, "ASSISTANT", "第一锁；第二队列；第三幂等");
        var result = assembler.assemble("系统", "第三个", List.of(), List.of(), List.of(user),
                List.of(), List.of(user, assistant), List.of(), AgentWebSearchResult.empty(),
                new AgentPromptBudget.Limits(4000, 1000));
        assertThat(result.history()).extracting(AgentConversationHistoryHit::messageId)
                .containsExactly(1L, 2L);
        assertThat(result.messages().getLast().text()).contains("\"conversationHistory\":[]");
    }

    @Test
    void evictsOldestWholeTurnAndKeepsTheNewestPairIntact() {
        var result = assembler.assemble("系统", "继续", List.of(), List.of(), List.of(), List.of(),
                List.of(row(1, 1, "USER", "旧问题"), row(2, 1, "ASSISTANT", "旧内容".repeat(2000)),
                        row(3, 2, "USER", "新问题"), row(4, 2, "ASSISTANT", "新回答")),
                List.of(), AgentWebSearchResult.empty(), new AgentPromptBudget.Limits(1000, 200));
        assertThat(result.history()).extracting(AgentConversationHistoryHit::messageId)
                .containsExactly(3L, 4L);
        assertThat(result.reduced()).isTrue();
        assertThat(result.estimatedInputTokens()).isLessThanOrEqualTo(1000);
        assertThat(result.messages().getLast().text()).contains("新问题", "新回答").doesNotContain("旧问题");
    }

    @Test
    void keepsRecentDialogueBeforeLowPrioritySourcesAndRevokesTheirCitationAuthority() {
        var source = new ResolvedArticleChunk(1, 1, 1, 0, "大资料", List.of(),
                "检索资料".repeat(3000), "a".repeat(64), "b".repeat(64));
        var result = assembler.assemble("系统", "第三个", List.of(source), List.of(), List.of(),
                List.of(), List.of(row(1, 1, "USER", "三个方案"), row(2, 1, "ASSISTANT", "第三幂等")),
                List.of(), AgentWebSearchResult.empty(), new AgentPromptBudget.Limits(1000, 200));
        assertThat(result.sources()).isEmpty();
        assertThat(result.history()).hasSize(2);
        assertThat(result.messages().getLast().text()).doesNotContain("大资料");
    }

    @Test
    void rejectsOversizedQuestionInsteadOfSilentlyCuttingIt() {
        assertThatThrownBy(() -> assembler.assemble("系统", "不可静默截断的用户要求".repeat(3000),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                AgentWebSearchResult.empty(), new AgentPromptBudget.Limits(1000, 200)))
                .isInstanceOf(AiExecutionException.class).hasMessageContaining("输入预算");
    }

    @Test
    void longChinesePromptCanExceedOldCharacterCapWhileRemainingWithinTokenBudget() {
        var result = assembler.assemble("系统", "解释这些事务细节".repeat(700), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), AgentWebSearchResult.empty(),
                new AgentPromptBudget.Limits(27648, 4096));
        assertThat(result.messages().getLast().text().length()).isGreaterThan(4000);
        assertThat(result.reduced()).isFalse();
        assertThat(result.estimatedInputTokens()).isLessThanOrEqualTo(27648);
    }

    @Test
    void removedTemporaryContextIsNotMisreportedAsPersonalEvidence() {
        var result = assembler.assemble("系统", "继续", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of("USER\t旧问题", "ASSISTANT\t" + "旧答案".repeat(3000)),
                AgentWebSearchResult.empty(), new AgentPromptBudget.Limits(500, 100));
        assertThat(result.hasPersonalContext()).isFalse();
        assertThat(result.messages().getLast().text()).contains("\"temporaryConversation\":[]");
        assertThat(result.messages().getLast().text()).contains("\"contextReduced\":true");
    }

    private static AgentConversationHistoryHit row(long id, long turn, String role, String content) {
        return new AgentConversationHistoryHit(id, turn, 9, role, content,
                LocalDateTime.parse("2026-09-08T00:00:00"));
    }

    @Test
    void loadsPastTwentyFourTurnsAndRestoresChronologicalOrder() {
        var calls = new java.util.ArrayList<Long>();
        var first = new AgentConversationPage(turns(25, 48), 25, false);
        var result = assembler.assemblePaged("系统", "继续", List.of(), List.of(), List.of(), List.of(),
                first, AgentWebSearchResult.empty(), new AgentPromptBudget.Limits(27648, 4096), cursor -> {
                    calls.add(cursor);
                    return new AgentConversationPage(turns(1, 24), 1, true);
                }, () -> true);
        assertThat(calls).containsExactly(25L);
        assertThat(result.history()).hasSize(96);
        assertThat(result.history().getFirst().turnId()).isEqualTo(1);
        assertThat(result.history().getLast().turnId()).isEqualTo(48);
        assertThat(result.history()).extracting(AgentConversationHistoryHit::messageId).isSorted();
    }

    @Test
    void fillsTheFittingPartOfAPageWithoutEvictingNewerTurnsOrEvidence() {
        var source = new ResolvedArticleChunk(1, 1, 1, 0, "必要资料", List.of(),
                "当前问题依据", "a".repeat(64), "b".repeat(64));
        var first = new AgentConversationPage(turns(9, 10), 9, false);
        var old = new java.util.ArrayList<AgentConversationHistoryHit>();
        old.add(row(2, 1, "USER", "非常久以前的问题"));
        old.add(row(3, 1, "ASSISTANT", "巨大历史".repeat(3000)));
        old.addAll(turns(7, 8));
        var result = assembler.assemblePaged("系统", "继续", List.of(source), List.of(), List.of(), List.of(),
                first, AgentWebSearchResult.empty(), new AgentPromptBudget.Limits(2000, 500),
                cursor -> new AgentConversationPage(old, 1, true), () -> true);
        assertThat(result.history()).extracting(AgentConversationHistoryHit::turnId)
                .containsExactly(7L, 7L, 8L, 8L, 9L, 9L, 10L, 10L);
        assertThat(result.sources()).containsExactly(source);
        assertThat(result.estimatedInputTokens()).isLessThanOrEqualTo(2000);
        assertThat(result.reduced()).isTrue();
    }

    @Test
    void continuesPastAFilteredEmptyPageUsingItsRawCursor() {
        var calls = new java.util.ArrayList<Long>();
        var result = assembler.assemblePaged("系统", "继续", List.of(), List.of(), List.of(), List.of(),
                new AgentConversationPage(List.of(), 50, false), AgentWebSearchResult.empty(),
                new AgentPromptBudget.Limits(3000, 500), cursor -> {
                    calls.add(cursor);
                    return cursor == 50 ? new AgentConversationPage(List.of(), 25, false)
                            : new AgentConversationPage(turns(1, 2), 1, true);
                }, () -> true);
        assertThat(calls).containsExactly(50L, 25L);
        assertThat(result.history()).hasSize(4);
    }

    @Test
    void timeBudgetStopsDatabaseReadsWithoutDroppingAlreadyLoadedHistory() {
        var result = assembler.assemblePaged("系统", "继续", List.of(), List.of(), List.of(), List.of(),
                new AgentConversationPage(turns(1, 2), 1, false), AgentWebSearchResult.empty(),
                new AgentPromptBudget.Limits(3000, 500), cursor -> {
                    throw new AssertionError("读取时间耗尽后不能继续访问数据库");
                }, () -> false);
        assertThat(result.history()).hasSize(4);
        assertThat(result.reduced()).isTrue();
    }

    private static List<AgentConversationHistoryHit> turns(int start, int end) {
        var result = new java.util.ArrayList<AgentConversationHistoryHit>();
        for (int turn = start; turn <= end; turn++) {
            result.add(row(turn * 2L, turn, "USER", "第" + turn + "轮问题"));
            result.add(row(turn * 2L + 1, turn, "ASSISTANT", "第" + turn + "轮回答"));
        }
        return result;
    }

    @Test
    void oversizedOlderTurnsInFirstPageDoNotDisplaceNecessarySources() {
        var source = new ResolvedArticleChunk(1, 1, 1, 0, "必要资料", List.of(),
                "当前问题依据", "a".repeat(64), "b".repeat(64));
        var first = new java.util.ArrayList<AgentConversationHistoryHit>();
        first.add(row(2, 1, "USER", "旧问题"));
        first.add(row(3, 1, "ASSISTANT", "巨大旧答案".repeat(3000)));
        first.addAll(turns(2, 2));
        var result = assembler.assemblePaged("系统", "继续", List.of(source), List.of(), List.of(), List.of(),
                new AgentConversationPage(first, 1, true), AgentWebSearchResult.empty(),
                new AgentPromptBudget.Limits(2000, 500), cursor -> {
                    throw new AssertionError("第一页已是最后一页");
                }, () -> true);
        assertThat(result.sources()).containsExactly(source);
        assertThat(result.history()).extracting(AgentConversationHistoryHit::turnId).containsExactly(2L, 2L);
    }

    @Test
    void optionalOlderPageTimeoutReturnsAlreadyLoadedContext() {
        var result = assembler.assemblePaged("系统", "继续", List.of(), List.of(), List.of(), List.of(),
                new AgentConversationPage(turns(1, 2), 1, false), AgentWebSearchResult.empty(),
                new AgentPromptBudget.Limits(3000, 500), cursor -> {
                    throw new org.springframework.dao.QueryTimeoutException("test timeout");
                }, () -> true);
        assertThat(result.history()).hasSize(4);
        assertThat(result.reduced()).isTrue();
    }

    /** 换小模型后最新整轮可能超限，但不能连本轮仍放得下的短检索依据一起丢掉。 */
    @Test
    void oversizedNewestTurnDoesNotEraseFittingEvidenceAfterSwitchingToSmallModel() {
        var source = new ResolvedArticleChunk(1, 1, 1, 0, "当前依据", List.of(),
                "本轮检索到的短资料", "a".repeat(64), "b".repeat(64));
        var page = new AgentConversationPage(List.of(
                row(2, 1, "USER", "问".repeat(2700)),
                row(3, 1, "ASSISTANT", "答".repeat(1800))), 1, false);
        var result = assembler.assemblePaged("系统", "解释当前资料", List.of(source), List.of(),
                List.of(), List.of(), page, AgentWebSearchResult.empty(),
                budget.limits("unknown", cumt.zongzuo.community.ai.userprovider.UserAiFundingSource.USER),
                cursor -> { throw new AssertionError("最新轮次无法保留时不补入更老轮次"); }, () -> true);
        assertThat(result.history()).isEmpty();
        assertThat(result.sources()).containsExactly(source);
        assertThat(result.messages().getLast().text()).contains("本轮检索到的短资料");
        assertThat(result.estimatedInputTokens()).isLessThanOrEqualTo(5120);
        assertThat(result.reduced()).isTrue();
    }

    @Test
    void rejectsNonAdvancingCursorInsteadOfLoopingForever() {
        assertThatThrownBy(() -> assembler.assemblePaged("系统", "继续", List.of(), List.of(), List.of(), List.of(),
                new AgentConversationPage(turns(9, 10), 9, false), AgentWebSearchResult.empty(),
                new AgentPromptBudget.Limits(3000, 500), cursor ->
                        new AgentConversationPage(List.of(), 9, false), () -> true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cursor");
    }
}
