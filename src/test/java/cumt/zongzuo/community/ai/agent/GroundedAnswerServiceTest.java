package cumt.zongzuo.community.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.retrieval.ArticleRetrievalQuery;
import cumt.zongzuo.community.ai.agent.retrieval.ArticleRetrievalResult;
import cumt.zongzuo.community.ai.agent.retrieval.HybridArticleRetrievalService;
import cumt.zongzuo.community.ai.agent.retrieval.RankedArticleChunk;
import cumt.zongzuo.community.ai.agent.retrieval.ResolvedArticleChunk;
import cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit;
import cumt.zongzuo.community.ai.agent.history.AgentConversationHistorySearchService;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryRecallService;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryView;
import cumt.zongzuo.community.ai.agent.react.*;

import cumt.zongzuo.community.ai.agent.planner.AgentReadOnlyTool;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSearchGateway;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSearchResult;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSource;
import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatGateway;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.userprovider.UserAiChatRouter;
import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;
import cumt.zongzuo.community.ai.userprovider.UserAiRoutedResult;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroundedAnswerServiceTest {

    private final HybridArticleRetrievalService retrieval = mock(HybridArticleRetrievalService.class);
    private final AiChatGateway gateway = mock(AiChatGateway.class);
    private final AgentMemoryRecallService memories = mock(AgentMemoryRecallService.class);
    private final AgentConversationHistorySearchService history =
            mock(AgentConversationHistorySearchService.class);
    private final AgentWebSearchGateway webSearch = mock(AgentWebSearchGateway.class);
    private final AtomicInteger calls = new AtomicInteger();
    private final ResolvedArticleChunk source = new ResolvedArticleChunk(31L, 301L, 3001L, 0,
            "MySQL locks", List.of("Transactions"),
            "Use SELECT FOR UPDATE to serialize writers around the current row.",
            "a".repeat(64), "b".repeat(64));

    @Test
    void returnsOnlyValidatedBackendGeneratedCitations() {
        retrievalResult(List.of(source));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"Use a row lock around the writer transaction.[1]","citations":[
                  {"marker":1,"sourceId":"A301:R3001:C31",
                   "quote":"Use SELECT FOR UPDATE to serialize writers"}]}
                """, "stop", 120, 32, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answer(9L, "request-1", "How do I serialize writers?",
                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.answer()).contains("[1]");
        assertThat(answer.citations()).containsExactly(new AgentCitation(1,
                "A301:R3001:C31", 301L, 3001L, 31L, "MySQL locks",
                "Use SELECT FOR UPDATE to serialize writers", "/article/301"));
        assertThat(answer.fundingSource()).isEqualTo(UserAiFundingSource.USER);
        assertThat(answer.provider()).isEqualTo("test");
        assertThat(answer.model()).isEqualTo("deepseek-test");
        assertThat(calls).hasValue(1);
    }

    @Test
    void acceptsABracketedCitationMarkerFromAnOpenAiCompatibleModel() {
        retrievalResult(List.of(source));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"Use a row lock around the writer transaction.[1]","citations":[
                  {"marker":"[1]","sourceId":"A301:R3001:C31",
                   "quote":"Use SELECT FOR UPDATE to serialize writers"}]}
                """, "stop", 120, 32, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answer(9L, "request-text-marker",
                "How do I serialize writers?", Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.citations()).extracting(AgentCitation::marker).containsExactly(1);
    }

    @Test
    void rejectsAnInventedCitationEvenWhenTheProviderReturnsValidJson() {
        retrievalResult(List.of(source));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"Invented claim.[1]","citations":[
                  {"marker":1,"sourceId":"A999:R999:C999","quote":"Invented quote"}]}
                """, "stop", 100, 20, "test", "deepseek-test"));

        assertThatThrownBy(() -> service().answer(9L, "request-2", "question",
                Instant.parse("2026-08-12T00:00:30Z")))
                .isInstanceOf(InvalidAgentAnswerException.class);
    }

    @Test
    void answersWithClearlyMarkedModelKnowledgeWhenCommunityEvidenceIsUnavailable() {
        retrievalResult(List.of());
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"可以先从问题目标和约束开始分析。","citations":[]}
                """, "stop", 80, 20, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answer(9L, "request-3", "unknown",
                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.answer()).startsWith("【模型通用知识】");
        assertThat(answer.citations()).isEmpty();
        verify(gateway).generate(any());
    }

    @Test
    void disabledMemoryIsNeitherReadNorSentToTheModel() {
        retrievalResult(List.of());
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"【模型通用知识】当前没有可使用的个人记忆。","citations":[]}
                """, "stop", 80, 20, "test", "deepseek-test"));
        GroundedAgentAnswer answer = service(false).answer(9L, "request-memory-off", "你记得我吗",
                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.finishReason()).isEqualTo("stop");
        verify(memories, never()).recall(any(Long.class), any(), any(Integer.class), any());
        verify(gateway).generate(any());
    }

    @Test
    void answersFromOwnerMemoryAndOldConversationWithoutCommunityCitations() {
        retrievalResult(List.of());
        when(memories.recall(9L, "你记得我喜欢什么，以及我说过的重话吗？", 6,
                Instant.parse("2026-08-12T00:00:30Z")))
                .thenReturn(List.of(new AgentMemoryView(71L, "PREFERENCE",
                        "我喜欢简洁的回答风格", 2L, "ACTIVE", null,
                        "CONVERSATION")));
        when(history.search(9L, "你记得我喜欢什么，以及我说过的重话吗？", 6))
                .thenReturn(List.of(new AgentConversationHistoryHit(81L, 801L, 9L, "USER",
                        "你是我用过最难用的助手",
                        LocalDateTime.parse("2026-01-02T03:04:05"))));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"你喜欢简洁回答；你曾说我是你用过最难用的助手。","citations":[]}
                """, "stop", 100, 20, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answer(9L, "request-personal",
                "你记得我喜欢什么，以及我说过的重话吗？",
                Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.citations()).isEmpty();
        assertThat(answer.memoryUses()).extracting(AgentMemoryUse::memoryId)
                .containsExactly(71L);
        assertThat(answer.historyUses()).extracting(AgentHistoryUse::messageId)
                .containsExactly(81L);
        var command = org.mockito.ArgumentCaptor.forClass(
                cumt.zongzuo.community.ai.provider.AiChatCommand.class);
        verify(gateway).generate(command.capture());
        assertThat(command.getValue().messages().toString())
                .contains("我喜欢简洁的回答风格", "你是我用过最难用的助手");
    }

    @Test
    void temporaryAnswerUsesOnlySuppliedSessionContextAndNeverReadsPersistentPersonalData() {
        retrievalResult(List.of());
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"你在这次临时对话里说过喜欢红色。","citations":[]}
                """, "stop", 80, 20, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answerTemporary(9L, "temporary-request",
                "我刚才说喜欢什么？", List.of("USER\t我喜欢红色"),
                Instant.parse("2026-08-12T00:00:30Z"));

        verify(memories, never()).recall(any(Long.class), any(), any(Integer.class), any());
        verify(history, never()).search(any(Long.class), any(), any(Integer.class));
        verify(history, never()).recentConversation(anyLong(), anyInt());
        verify(history, never()).conversationPage(anyLong(), anyLong(), anyInt());
        verify(history, never()).recentSummaries(anyLong(), anyInt());
        var command = org.mockito.ArgumentCaptor.forClass(
                cumt.zongzuo.community.ai.provider.AiChatCommand.class);
        verify(gateway).generate(command.capture());
        assertThat(command.getValue().messages().toString()).contains("我喜欢红色");
        assertThat(answer.memoryUses()).isEmpty();
        assertThat(answer.historyUses()).isEmpty();
    }

    @Test
    void enabledWebSearchRunsEvenWithCommunitySourcesAndKeepsSourceCategoriesSeparate() {
        retrievalResult(List.of(source));
        when(webSearch.search(any(), any())).thenReturn(new AgentWebSearchResult(
                "网上资料补充：MySQL 官方建议保持事务简短。",
                List.of(new AgentWebSource(1, "MySQL 事务文档",
                        "https://dev.mysql.com/doc/refman/8.4/en/commit.html", "MySQL"))));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"【站内文章】可以用行锁串行化写入。[1]\\n\\n【联网搜索】同时应保持事务简短。[W1]","citations":[
                  {"marker":1,"sourceId":"A301:R3001:C31",
                   "quote":"Use SELECT FOR UPDATE to serialize writers"}]}
                """, "stop", 120, 32, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answer(9L, "request-web", "如何控制并发写入？",
                true, Instant.parse("2026-08-12T00:00:30Z"));

        verify(webSearch).search("如何控制并发写入？", Instant.parse("2026-08-12T00:00:30Z"));
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.webSources()).containsExactly(new AgentWebSource(1, "MySQL 事务文档",
                "https://dev.mysql.com/doc/refman/8.4/en/commit.html", "MySQL"));
        var command = org.mockito.ArgumentCaptor.forClass(
                cumt.zongzuo.community.ai.provider.AiChatCommand.class);
        verify(gateway).generate(command.capture());
        assertThat(command.getValue().messages().toString())
                .contains("网上资料补充", "dev.mysql.com", "Use SELECT FOR UPDATE");
    }

    @Test
    void disabledWebSearchNeverCallsTheExternalGateway() {
        retrievalResult(List.of());
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"【模型通用知识】这是不联网的回答。","citations":[]}
                """, "stop", 20, 8, "test", "deepseek-test"));

        service().answer(9L, "request-web-off", "不要联网", false,
                Instant.parse("2026-08-12T00:00:30Z"));

        verify(webSearch, never()).search(any(), any());
    }

    @Test
    void webOnlyAnswerKeepsItsWebLabelInsteadOfBeingRelabeledAsModelKnowledge() {
        retrievalResult(List.of());
        when(webSearch.search(any(), any())).thenReturn(new AgentWebSearchResult(
                "北京今天晴朗。[W1]", List.of(new AgentWebSource(1, "天气资料",
                "https://example.com/weather", "Example"))));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"【联网搜索】北京今天晴朗。[W1]","citations":[]}
                """, "stop", 30, 8, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answer(9L, "request-web-only", "北京天气",
                true, Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.answer()).startsWith("【联网搜索】");
        assertThat(answer.webSources()).extracting(AgentWebSource::index).containsExactly(1);
    }

    @Test
    void rejectsAnInventedWebMarker() {
        retrievalResult(List.of());
        when(webSearch.search(any(), any())).thenReturn(new AgentWebSearchResult(
                "只提供了一个联网来源。[W1]", List.of(new AgentWebSource(1, "来源一",
                "https://example.com/one", "Example"))));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"【联网搜索】这是一个伪造来源。[W9]","citations":[]}
                """, "stop", 30, 8, "test", "deepseek-test"));

        assertThatThrownBy(() -> service().answer(9L, "request-web-invented", "问题",
                true, Instant.parse("2026-08-12T00:00:30Z")))
                .isInstanceOf(InvalidAgentAnswerException.class);
    }

    @Test
    void removesOnlyAuthorizedRedundantWebCitationsReturnedByTheModel() {
        retrievalResult(List.of());
        when(webSearch.search(any(), any())).thenReturn(new AgentWebSearchResult(
                "广州今天有雨。[W2]", List.of(new AgentWebSource(2, "广州天气",
                "https://example.com/guangzhou", "Example"))));
        when(gateway.generate(any())).thenReturn(new AiChatResult("""
                {"answer":"【联网搜索】广州今天有雨。[W2]","citations":[
                  {"marker":"[W2]","sourceId":"webSearch","quote":"广州今天有雨"}]}
                """, "stop", 30, 8, "test", "deepseek-test"));

        GroundedAgentAnswer answer = service().answer(9L, "request-web-redundant", "广州天气",
                true, Instant.parse("2026-08-12T00:00:30Z"));

        assertThat(answer.citations()).isEmpty();
        assertThat(answer.webSources()).extracting(AgentWebSource::index).containsExactly(2);
    }

    /** 若执行器忽略观察、禁止二次文章检索或覆盖旧依据，本测试就会失败。 */
    @Test
    void reactsToMemoryContentAndSearchesAgainWithNewQuery() {
        retrievalResult(List.of(source));
        when(memories.recall(anyLong(),any(),anyInt(),any())).thenReturn(List.of(
                new AgentMemoryView(72L,"PREFERENCE","用户正在学习 MVCC",1L,"ACTIVE",null,"CONVERSATION")));
        var planner = decisionProvider();
        when(planner.decide(anyLong(),any(),any(),any(),anyBoolean(),anyBoolean(),anyInt(),anyInt(),any(),any(),any()))
                .thenAnswer(invocation -> {
                    int step=invocation.getArgument(6);
                    List<AgentToolObservation> observations=invocation.getArgument(8);
                    if (step==1) return action(AgentReadOnlyTool.LONG_TERM_MEMORY,"目前学习进度");
                    if (step==2) {
                        assertThat(observations.toString()).contains("用户正在学习 MVCC");
                        return action(AgentReadOnlyTool.COMMUNITY_ARTICLES,"MVCC 入门");
                    }
                    assertThat(observations.toString()).contains("Use SELECT FOR UPDATE");
                    return new AgentReactDecision(null);
                });
        simpleAnswer();
        var answer=service(true,planner).answer(9,"react","推荐下一步学习的文章",false,DEADLINE);
        var queries=org.mockito.ArgumentCaptor.forClass(ArticleRetrievalQuery.class);
        verify(retrieval,org.mockito.Mockito.times(2)).retrieve(queries.capture());
        assertThat(queries.getAllValues()).extracting(ArticleRetrievalQuery::query)
                .containsExactly("推荐下一步学习的文章","MVCC 入门");
        assertThat(answer.memoryUses()).extracting(AgentMemoryUse::content).containsExactly("用户正在学习 MVCC");
    }

    @Test
    void duplicateActionsStopWithoutHittingToolAgain() {
        retrievalResult(List.of()); simpleAnswer();
        var planner=decisionProvider();
        when(planner.decide(anyLong(),any(),any(),any(),anyBoolean(),anyBoolean(),anyInt(),anyInt(),any(),any(),any()))
                .thenReturn(action(AgentReadOnlyTool.COMMUNITY_ARTICLES,"问题"));
        service(true,planner).answer(9,"duplicate","问题",false,DEADLINE);
        verify(retrieval).retrieve(any());
        verify(planner,org.mockito.Mockito.times(2)).decide(anyLong(),any(),any(),any(),anyBoolean(),anyBoolean(),
                anyInt(),anyInt(),any(),any(),any());
    }

    @Test
    void toolFailureIsObservedAndAnotherSourceCanBeChosen() {
        when(retrieval.retrieve(any())).thenThrow(new IllegalStateException("private SQL details"));
        when(history.search(9L,"事务",6)).thenReturn(List.of(new AgentConversationHistoryHit(
                82,802,9,"USER","之前问过事务隔离",LocalDateTime.parse("2026-01-02T03:04:05"))));
        var planner=decisionProvider();
        when(planner.decide(anyLong(),any(),any(),any(),anyBoolean(),anyBoolean(),anyInt(),anyInt(),any(),any(),any()))
                .thenAnswer(invocation -> {
                    List<AgentToolObservation> observations=invocation.getArgument(8);
                    assertThat(observations.toString()).contains("ERROR").doesNotContain("private SQL details");
                    return (int)invocation.getArgument(6)==1
                            ? action(AgentReadOnlyTool.CONVERSATION_HISTORY,"事务") : new AgentReactDecision(null);
                });
        simpleAnswer();
        assertThat(service(true,planner).answer(9,"failed-tool","问题",false,DEADLINE).historyUses()).hasSize(1);
    }

    @Test
    void maliciousProviderCannotReadPersistentDataInTemporaryModeOrSearchWhenDisabled() {
        retrievalResult(List.of(source)); simpleAnswer();
        var planner=decisionProvider();
        when(planner.decide(anyLong(),any(),any(),any(),anyBoolean(),anyBoolean(),anyInt(),anyInt(),any(),any(),any()))
                .thenReturn(action(AgentReadOnlyTool.LONG_TERM_MEMORY,"秘密"),
                        action(AgentReadOnlyTool.WEB_SEARCH,"秘密"));
        service(true,planner).answerTemporary(9,"temporary-denied","问题",List.of("当前临时上下文"),false,DEADLINE);
        org.mockito.Mockito.verifyNoInteractions(memories,history,webSearch);
    }

    @Test
    void disabledMemoryCannotBeReenabledByTheDecisionModel() {
        retrievalResult(List.of()); simpleAnswer();
        var planner=decisionProvider();
        when(planner.decide(anyLong(),any(),any(),any(),anyBoolean(),anyBoolean(),anyInt(),anyInt(),any(),any(),any()))
                .thenReturn(action(AgentReadOnlyTool.LONG_TERM_MEMORY,"偏好"));
        service(false,planner).answer(9,"memory-off","问题",false,DEADLINE);
        org.mockito.Mockito.verifyNoInteractions(memories);
    }

    @Test
    void modelCanFinishWithoutConsumingAllConfiguredSteps() {
        retrievalResult(List.of()); simpleAnswer();
        var planner=decisionProvider();
        service(true,planner).answer(9,"finish","问题",false,DEADLINE);
        verify(planner).decide(anyLong(),any(),any(),any(),anyBoolean(),anyBoolean(),anyInt(),anyInt(),any(),any(),any());
        verify(retrieval).retrieve(any());
    }

    private static final Instant DEADLINE=Instant.parse("2026-08-12T00:00:30Z");

    @Test
    void wrappedToolCancellationCannotBecomeAnErrorObservationAndContinue() {
        var cancelled = new cumt.zongzuo.community.ai.runtime.AiExecutionException(
                cumt.zongzuo.community.ai.runtime.AiExecutionErrorReason.CANCELLED,"cancelled");
        when(retrieval.retrieve(any())).thenThrow(cancelled);
        simpleAnswer();
        var planner=decisionProvider();
        assertThatThrownBy(()->service(true,planner).answer(9,"cancelled-tool","问题",false,DEADLINE))
                .isSameAs(cancelled);
        verify(planner,never()).decide(anyLong(),any(),any(),any(),anyBoolean(),anyBoolean(),anyInt(),anyInt(),any(),any(),any());
        verify(gateway,never()).generate(any());
    }

    @Test
    void memoryChangeDuringRetrievalPreventsSendingStaleContextToTheModel() {
        var epoch=new java.util.concurrent.atomic.AtomicLong(1);
        when(memories.epoch(9L)).thenAnswer(invocation -> epoch.get());
        when(retrieval.retrieve(any())).thenAnswer(invocation -> {
            epoch.incrementAndGet(); // 模拟另一个请求删除或暂停记忆。
            return new ArticleRetrievalResult(0,0,true,true,List.of(),List.of());
        });
        simpleAnswer();
        assertThatThrownBy(()->service(true).answer(9,"epoch","问题",false,DEADLINE))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        verify(gateway,never()).generate(any());
    }

    @Test
    void externalSearchQueryIsGeneratedWithoutPrivateMemoryOrPrivateProposedQuery() {
        retrievalResult(List.of()); simpleAnswer();
        when(memories.recall(anyLong(),any(),anyInt(),any())).thenReturn(List.of(
                new AgentMemoryView(72,"PROFILE","秘密喜好与联系方式",1,"ACTIVE",null,"CONVERSATION")));
        when(webSearch.search(any(),any())).thenReturn(new AgentWebSearchResult(
                "MVCC 公开说明[W1]",List.of(new AgentWebSource(1,"公开资料","https://example.com/mvcc","站点"))));
        var provider=decisionProvider();
        when(provider.decide(anyLong(),any(),any(),any(),anyBoolean(),anyBoolean(),anyInt(),anyInt(),any(),any(),any()))
                .thenReturn(action(AgentReadOnlyTool.LONG_TERM_MEMORY,"学习情况"),
                        action(AgentReadOnlyTool.WEB_SEARCH,"秘密喜好与联系方式"),new AgentReactDecision(null));
        when(provider.publicWebQuery(anyLong(),any(),any(),any(),any(),any())).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(2,String.class)).isEqualTo("解释 MVCC");
            List<AgentToolObservation> publicData=invocation.getArgument(3);
            assertThat(publicData.toString()).doesNotContain("秘密喜好","联系方式","学习情况");
            assertThat(publicData).allMatch(o->o.call().query().equals("解释 MVCC"));
            return "MVCC 公开说明";
        });
        service(true,provider).answer(9,"public-query","解释 MVCC",true,DEADLINE);
        var queries=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(webSearch,org.mockito.Mockito.times(2)).search(queries.capture(),any());
        assertThat(queries.getAllValues()).containsExactly("解释 MVCC","MVCC 公开说明");
    }

    /** 连真实决策适配器一起测，不仅用一个会返回固定动作的 Planner 替身。 */
    @Test
    void actualDecisionGatewayAndAnswerServiceCompleteTheObservationLoop() {
        retrievalResult(List.of(source));
        when(memories.recall(anyLong(),any(),anyInt(),any())).thenReturn(List.of(
                new AgentMemoryView(72,"GOAL","正在学习 MVCC",1,"ACTIVE",null,"CONVERSATION")));
        when(gateway.generate(any())).thenAnswer(invocation -> {
            var command=invocation.getArgument(0,cumt.zongzuo.community.ai.provider.AiChatCommand.class);
            if (command.messages().getFirst().text().contains("ReAct 决策器")) {
                var data=new ObjectMapper().readTree(command.messages().getLast().text());
                int step=data.path("step").asInt();
                String json;
                if (step==1) json="{\"action\":\"CALL\",\"tool\":\"LONG_TERM_MEMORY\",\"query\":\"学习目标\"}";
                else if (step==2) {
                    assertThat(data.path("observations").toString()).contains("正在学习 MVCC");
                    json="{\"action\":\"CALL\",\"tool\":\"COMMUNITY_ARTICLES\",\"query\":\"MVCC 入门\"}";
                } else json="{\"action\":\"FINISH\"}";
                return new AiChatResult(json,"stop",80,20,"test","deepseek-test");
            }
            return new AiChatResult("""
                    {"answer":"【站内文章】可以先看这一篇。[1]","citations":[
                    {"marker":1,"sourceId":"A301:R3001:C31","quote":"Use SELECT FOR UPDATE to serialize writers"}]}
                    ""","stop",100,30,"test","deepseek-test");
        });
        var provider=new GatewayReActDecisionProvider(new DirectExecutor(),new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"),ZoneOffset.UTC),Duration.ofSeconds(6),6,8,
                new cumt.zongzuo.community.ai.agent.context.AgentPromptBudget(
                        new cumt.zongzuo.community.ai.agent.context.AgentContextProperties()),400_000);
        var answer=service(true,provider).answer(9,"actual-react","下一步学什么",false,DEADLINE);
        assertThat(answer.citations()).extracting(AgentCitation::sourceId).containsExactly("A301:R3001:C31");
        assertThat(answer.memoryUses()).hasSize(1);
        assertThat(calls).hasValue(4); // 三次决策，一次最终回答。
    }

    @Test
    void executionLayerEnforcesToolBudgetEvenWhenModelKeepsFindingNewEvidence() {
        retrievalResult(List.of()); simpleAnswer();
        var counter=new AtomicInteger();
        when(history.search(anyLong(),any(),anyInt())).thenAnswer(invocation -> List.of(
                new AgentConversationHistoryHit(counter.incrementAndGet(),802,9,"USER","旧历史",
                        LocalDateTime.parse("2026-01-02T03:04:05"))));
        var provider=decisionProvider();
        when(provider.maxToolCalls()).thenReturn(3);
        when(provider.maxRounds()).thenReturn(1000);
        when(provider.decide(anyLong(),any(),any(),any(),anyBoolean(),anyBoolean(),anyInt(),anyInt(),any(),any(),any()))
                .thenAnswer(invocation -> action(AgentReadOnlyTool.CONVERSATION_HISTORY,"查询"+invocation.getArgument(6)));
        var answer=service(true,provider).answer(9,"budget","问题",false,DEADLINE);
        assertThat(counter).hasValue(2); // 加上初始文章检索，恰好三次工具调用。
        assertThat(answer.historyUses()).hasSize(2);
    }

    @Test
    void cancelledTurnCannotStartAnotherDecisionOrFinalAnswer() {
        var running=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(retrieval.retrieve(any())).thenAnswer(invocation -> {
            running.set(false);
            return new ArticleRetrievalResult(1,0,true,false,List.of(source),List.of());
        });
        var provider=decisionProvider();
        assertThatThrownBy(()->service(true,provider).answerTemporary(9,"cancel","问题",List.of(),false,
                DEADLINE,running::get)).isInstanceOf(java.util.concurrent.CancellationException.class);
        org.mockito.Mockito.verifyNoInteractions(gateway);
        verify(provider,never()).decide(anyLong(),any(),any(),any(),anyBoolean(),anyBoolean(),anyInt(),anyInt(),any(),any(),any());
    }

    private AgentReactDecisionProvider decisionProvider() {
        var provider=mock(AgentReactDecisionProvider.class);
        when(provider.maxRounds()).thenReturn(6);
        when(provider.maxToolCalls()).thenReturn(8);
        when(provider.decide(anyLong(),any(),any(),any(),anyBoolean(),anyBoolean(),anyInt(),anyInt(),any(),any(),any()))
                .thenReturn(new AgentReactDecision(null));
        return provider;
    }

    private AgentReactDecision action(AgentReadOnlyTool tool,String query) {
        return new AgentReactDecision(new AgentToolCall(tool,query));
    }

    private void simpleAnswer() {
        when(gateway.generate(any())).thenReturn(new AiChatResult(
                "{\"answer\":\"整理好了\",\"citations\":[]}", "stop",80,20,"test","deepseek-test"));
    }

    private void retrievalResult(List<ResolvedArticleChunk> chunks) {
        when(retrieval.retrieve(any(ArticleRetrievalQuery.class))).thenReturn(new ArticleRetrievalResult(
                chunks.size(), chunks.size(), true, true, chunks,
                chunks.stream().map(chunk -> new RankedArticleChunk(chunk, .03, 1, 1)).toList()));
    }

    /** 验证生产回答链路真的向前翻页，而不是仅让独立组装器具备翻页能力。 */
    @Test
    void sendsMoreThanTwentyFourTurnsThroughTheActualAnswerPipeline() {
        retrievalResult(List.of());
        var newest = new java.util.ArrayList<AgentConversationHistoryHit>();
        var oldest = new java.util.ArrayList<AgentConversationHistoryHit>();
        for (int turn = 1; turn <= 36; turn++) {
            var target = turn <= 12 ? oldest : newest;
            target.add(new AgentConversationHistoryHit(turn * 2, turn, 9, "USER", "问题" + turn,
                    LocalDateTime.parse("2026-08-12T00:00:00")));
            target.add(new AgentConversationHistoryHit(turn * 2 + 1, turn, 9, "ASSISTANT", "回答" + turn,
                    LocalDateTime.parse("2026-08-12T00:00:01")));
        }
        when(history.conversationPage(9, Long.MAX_VALUE, 24)).thenReturn(
                new cumt.zongzuo.community.ai.agent.history.AgentConversationPage(newest, 13, false));
        when(history.conversationPage(9, 13, 24)).thenReturn(
                new cumt.zongzuo.community.ai.agent.history.AgentConversationPage(oldest, 1, true));
        when(gateway.generate(any())).thenAnswer(invocation -> {
            var command = invocation.getArgument(0, cumt.zongzuo.community.ai.provider.AiChatCommand.class);
            String json = command.messages().getLast().text().split("\\n", 2)[1];
            var messages = new ObjectMapper().readTree(json).path("recentConversation");
            assertThat(messages).hasSize(72);
            assertThat(messages.get(0).path("content").asText()).isEqualTo("问题1");
            assertThat(messages.get(71).path("content").asText()).isEqualTo("回答36");
            return new AiChatResult("{\"answer\":\"根据之前的讨论继续\",\"citations\":[]}",
                    "stop", 5000, 100, "test", "deepseek-test");
        });
        assertThat(service().answer(9, "paged-answer", "继续",
                Instant.parse("2026-08-12T00:00:30Z")).historyUses()).hasSize(72);
    }

    /** Planner 即使只选文章，第三个/继续等追问仍必须看到上一轮原始问答。 */
    @Test
    void followUpAlwaysCarriesRecentTurnsWithoutAHistoryToolCall() {
        retrievalResult(List.of());
        when(webSearch.search(any(), any())).thenReturn(AgentWebSearchResult.empty());
        var planner = decisionProvider();
        when(history.conversationPage(9L, Long.MAX_VALUE, 24)).thenReturn(
                new cumt.zongzuo.community.ai.agent.history.AgentConversationPage(List.of(
                new AgentConversationHistoryHit(10, 1, 9, "USER", "给三个事务优化方案",
                        LocalDateTime.parse("2026-08-12T00:00:00")),
                new AgentConversationHistoryHit(11, 1, 9, "ASSISTANT", "第一缩短事务；第二加索引；第三异步化",
                        LocalDateTime.parse("2026-08-12T00:00:01"))), 1, true));
        when(gateway.generate(any())).thenAnswer(invocation -> {
            var command = invocation.getArgument(0,
                    cumt.zongzuo.community.ai.provider.AiChatCommand.class);
            assertThat(command.messages().toString()).contains("recentConversation",
                    "给三个事务优化方案", "第三异步化", "把第三个展开");
            assertThat(command.maxOutputTokens()).isPositive();
            return new AiChatResult("{\"answer\":\"第三项是异步化\",\"citations\":[]}",
                    "stop", 200, 30, "test", "deepseek-test");
        });
        var answer = service(true, planner).answer(9L, "follow-up", "把第三个展开", true,
                Instant.parse("2026-08-12T00:00:30Z"));
        assertThat(answer.answer()).contains("第三项是异步化");
        assertThat(answer.historyUses()).extracting(AgentHistoryUse::messageId).containsExactly(10L, 11L);
        var query = org.mockito.ArgumentCaptor.forClass(ArticleRetrievalQuery.class);
        verify(retrieval).retrieve(query.capture());
        assertThat(query.getValue().query()).contains("给三个事务优化方案", "把第三个展开");
        // 检索只使用前 20 秒，为最终回答保留 10 秒，而不是耗尽整个请求。
        verify(webSearch).search("把第三个展开", Instant.parse("2026-08-12T00:00:20Z"));
    }

    /** 检索结果再多也不能无限扩大最终请求；裁掉的文章不得继续充当引用依据。 */
    @Test
    void oversizedEvidenceIsRemovedBeforeSendingThePrompt() {
        var huge = new ResolvedArticleChunk(32L, 302L, 3002L, 0, "巨大资料",
                List.of(), "低相关资料。".repeat(30_000), "a".repeat(64), "b".repeat(64));
        retrievalResult(List.of(huge));
        when(gateway.generate(any())).thenAnswer(invocation -> {
            var command = invocation.getArgument(0,
                    cumt.zongzuo.community.ai.provider.AiChatCommand.class);
            assertThat(command.messages().stream().mapToInt(m -> m.text().length()).sum())
                    .isLessThan(100_000);
            assertThat(command.messages().toString()).contains("解释事务").doesNotContain("巨大资料");
            return new AiChatResult("{\"answer\":\"事务是一组原子操作\",\"citations\":[]}",
                    "stop", 100, 20, "test", "deepseek-test");
        });
        assertThat(service().answer(9L, "budget-test", "解释事务",
                Instant.parse("2026-08-12T00:00:30Z")).citations()).isEmpty();
    }

    /** 摘要是可选资料，数据库超时不能使已经取得原文和文章证据的整轮回答失败。 */
    @Test
    void summaryTimeoutKeepsRecentDialogueAndArticleEvidence() {
        retrievalResult(List.of(source));
        var planner = decisionProvider();
        when(history.conversationPage(9L, Long.MAX_VALUE, 24)).thenReturn(
                new cumt.zongzuo.community.ai.agent.history.AgentConversationPage(List.of(
                        new AgentConversationHistoryHit(10, 1, 9, "USER", "解释行锁",
                                LocalDateTime.parse("2026-08-12T00:00:00")),
                        new AgentConversationHistoryHit(11, 1, 9, "ASSISTANT", "行锁保护当前记录",
                                LocalDateTime.parse("2026-08-12T00:00:01"))), 1, true));
        when(history.recentSummaries(9L, 3))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("summary unavailable"));
        when(gateway.generate(any())).thenAnswer(invocation -> {
            var command = invocation.getArgument(0,
                    cumt.zongzuo.community.ai.provider.AiChatCommand.class);
            var data = new ObjectMapper().readTree(command.messages().getLast().text().split("\\n", 2)[1]);
            assertThat(data.path("recentConversation")).hasSize(2);
            assertThat(data.path("sources")).hasSize(1);
            assertThat(data.path("episodeSummaries")).isEmpty();
            return new AiChatResult("{\"answer\":\"继续解释行锁\",\"citations\":[]}",
                    "stop", 200, 30, "test", "deepseek-test");
        });
        var result = new java.util.concurrent.atomic.AtomicReference<GroundedAgentAnswer>();
        org.assertj.core.api.Assertions.assertThatCode(() -> result.set(
                service(true, planner).answer(9L, "summary-timeout", "继续",
                        Instant.parse("2026-08-12T00:00:30Z")))).doesNotThrowAnyException();
        assertThat(result.get().historyUses()).extracting(AgentHistoryUse::messageId)
                .containsExactly(10L, 11L);
    }

    private GroundedAnswerService service() {
        return service(true);
    }

    private GroundedAnswerService service(boolean memoryEnabled) {
        return service(memoryEnabled, null);
    }

    private GroundedAnswerService service(boolean memoryEnabled,
                                          AgentReactDecisionProvider planner) {
        return service(memoryEnabled,planner,null);
    }

    private GroundedAnswerService service(boolean memoryEnabled, AgentReactDecisionProvider planner,
            cumt.zongzuo.community.ai.agent.context.AgentCompactionGraph compaction) {
        UserAiChatRouter router = new UserAiChatRouter() {
            @Override public UserAiRoutedResult generate(long userId,
                    cumt.zongzuo.community.ai.provider.AiChatCommand command) {
                return new UserAiRoutedResult(gateway.generate(command),UserAiFundingSource.USER);
            }
            @Override public cumt.zongzuo.community.ai.userprovider.PreparedUserAiChat prepare(long userId,String model) {
                // 与真实 BYOK 路由一致：预算所见的资金来源和实际请求来源必须相同。
                return new cumt.zongzuo.community.ai.userprovider.PreparedUserAiChat(
                        model,UserAiFundingSource.USER,command->generate(userId,command));
            }
        };
        return new GroundedAnswerService(retrieval, new DirectExecutor(), router,
                new GroundedAnswerParser(new ObjectMapper()),
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                "deepseek-test", Duration.ofSeconds(30), memories, history, memoryEnabled,
                webSearch, planner,
                new cumt.zongzuo.community.ai.agent.context.AgentPromptBudget(
                        new cumt.zongzuo.community.ai.agent.context.AgentContextProperties()),400_000,compaction);
    }

    @Test
    void persistentAnswerUsesPreparedContextBeforeRetrievalAndGeneration() {
        var graph=mock(cumt.zongzuo.community.ai.agent.context.AgentCompactionGraph.class);
        var run=java.util.UUID.randomUUID();
        when(graph.prepare(anyLong(),any(),any(),any(),any())).thenReturn(
                new cumt.zongzuo.community.ai.agent.context.AgentCompactionGraph.Prepared(
                        cumt.zongzuo.community.ai.agent.history.AgentConversationPage.empty(),
                        List.of(new cumt.zongzuo.community.ai.agent.history.AgentEpisodeSummaryView(
                                1,1,"用户正在学习数据库事务",LocalDateTime.parse("2026-08-12T00:00:00")))));
        retrievalResult(List.of());
        when(gateway.generate(any())).thenAnswer(call -> {
            var command=call.getArgument(0,cumt.zongzuo.community.ai.provider.AiChatCommand.class);
            assertThat(command.messages().toString()).contains("用户正在学习数据库事务");
            return new AiChatResult("{\"answer\":\"继续讲解事务\",\"citations\":[]}","stop",100,20,"test","deepseek-test");
        });
        service(false,null,graph).answerPersistent(9,run,"继续",false,Instant.parse("2026-08-12T00:00:30Z"));
        var order=org.mockito.Mockito.inOrder(graph,retrieval,gateway);
        order.verify(graph).prepare(anyLong(),any(),any(),any(),any());
        order.verify(retrieval).retrieve(any());
        order.verify(gateway).generate(any());
        verify(history,never()).conversationPage(anyLong(),anyLong(),anyInt());
        verify(history,never()).recentSummaries(anyLong(),anyInt());
    }

    @Test
    void compactionFailurePreventsRetrievalAndFinalModelCall() {
        var graph=mock(cumt.zongzuo.community.ai.agent.context.AgentCompactionGraph.class);
        when(graph.prepare(anyLong(),any(),any(),any(),any())).thenThrow(new IllegalStateException("Memory index unavailable"));
        assertThatThrownBy(()->service(true,null,graph).answerPersistent(9,java.util.UUID.randomUUID(),
                "继续",false,Instant.parse("2026-08-12T00:00:30Z"))).hasMessageContaining("index unavailable");
        org.mockito.Mockito.verifyNoInteractions(retrieval,gateway,memories);
    }

    @Test
    void temporaryAnswerNeverEntersPersistentCompaction() {
        var graph=mock(cumt.zongzuo.community.ai.agent.context.AgentCompactionGraph.class);
        retrievalResult(List.of());
        when(gateway.generate(any())).thenReturn(new AiChatResult("{\"answer\":\"临时回答\",\"citations\":[]}",
                "stop",100,20,"test","deepseek-test"));
        service(true,null,graph).answerTemporary(9,"temporary","问题",List.of(),false,
                Instant.parse("2026-08-12T00:00:30Z"));
        org.mockito.Mockito.verifyNoInteractions(graph,memories,history);
    }

    @Test
    void memoryToolFailureIsNotSilentlyDowngradedToModelKnowledge() {
        var planner=decisionProvider();
        retrievalResult(List.of(source));
        when(planner.decide(anyLong(),any(),any(),any(),anyBoolean(),anyBoolean(),anyInt(),anyInt(),any(),any(),any()))
                .thenReturn(action(AgentReadOnlyTool.LONG_TERM_MEMORY,"偏好"));
        when(memories.recall(anyLong(),any(),anyInt(),any())).thenThrow(new IllegalStateException("Embedding unavailable"));
        assertThatThrownBy(()->service(true,planner).answer(9,"memory-failure","偏好",false,
                Instant.parse("2026-08-12T00:00:30Z"))).hasMessageContaining("Embedding unavailable");
        verify(gateway,never()).generate(any());
    }

    private final class DirectExecutor implements AiCapabilityExecutor {
        @Override
        public <T> T execute(cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                             CheckedSupplier<T> operation) {
            assertThat(context.capability()).isEqualTo(AiCapability.AGENT);
            calls.incrementAndGet();
            try {
                return operation.get();
            } catch (Throwable error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public <A, T> T execute(cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                                AttemptObserver<A, T> observer, AttemptOperation<A, T> operation) {
            throw new UnsupportedOperationException();
        }
    }
}
