package cumt.zongzuo.community.ai.agent.context;

import cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit;
import cumt.zongzuo.community.ai.agent.memory.AgentMemorySafetyPolicy;
import cumt.zongzuo.community.ai.userprovider.PreparedUserAiChat;
import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 Alibaba Graph 执行器，验证模型、数据库与上下文启用的顺序。 */
class AgentCompactionGraphTest {
    final Instant now = Instant.parse("2026-09-09T00:00:00Z");
    final UUID run = UUID.randomUUID();
    final AgentCompactionStore store = mock(AgentCompactionStore.class);
    final AgentCompactionModel model = mock(AgentCompactionModel.class);
    final PreparedUserAiChat route = new PreparedUserAiChat("test", UserAiFundingSource.PLATFORM,
            command -> { throw new AssertionError("This fixture must not call a real model"); });
    final AgentCompactionStore.Snapshot initial = new AgentCompactionStore.Snapshot(1, 1, 0, true, 0, 0, "");

    AgentCompactionGraph graph() {
        AgentContextProperties p = new AgentContextProperties();
        p.setPlatformWindowTokens(8192);
        return new AgentCompactionGraph(store, model, new AgentPromptBudget(p),
                new AgentMemorySafetyPolicy(), Clock.fixed(now, ZoneOffset.UTC), 400_000);
    }

    List<AgentConversationHistoryHit> turns(int first, int count, boolean large) {
        List<AgentConversationHistoryHit> result = new ArrayList<>();
        for (int i=first;i<first+count;i++) {
            result.add(new AgentConversationHistoryHit(i*2L, i, 1, "USER", "问题"+i, LocalDateTime.now()));
            result.add(new AgentConversationHistoryHit(i*2L+1, i, 1, "ASSISTANT",
                    large ? "这里是需要保留的解释。".repeat(180) : "回答"+i, LocalDateTime.now()));
        }
        return result;
    }

    @Test void smallContextKeepsOriginalTurnsWithoutCallingModel() {
        var rows = turns(1,2,false);
        when(store.snapshot(1)).thenReturn(initial);
        when(store.recent(1,1,3)).thenReturn(rows);
        when(store.messages(1,1,0,1,24)).thenReturn(List.of());
        var result = graph().prepare(1,run,"继续",route,now.plusSeconds(90));
        assertThat(result.recent().messages()).containsExactlyElementsOf(rows);
        assertThat(result.summaries()).isEmpty();
        verifyNoInteractions(model);
    }

    @Test void compactsBeforeAnswerAndKeepsLastThreeCompleteTurns() {
        when(model.fits(any())).thenReturn(true);
        var recent = turns(8,3,false);
        var old = turns(1,2,true);
        var staged = new AgentCompactionStore.Batch(10,1,1,"旧轮次摘要",true);
        when(store.snapshot(1)).thenReturn(initial,
                new AgentCompactionStore.Snapshot(1,1,1,true,0,1,"旧轮次摘要"));
        when(store.recent(1,1,3)).thenReturn(recent);
        when(store.messages(1,1,0,8,24)).thenReturn(old);
        when(store.messages(1,1,1,8,24)).thenReturn(old.subList(2,4));
        when(model.extract(any())).thenReturn(new AgentCompactionModel.Extraction("旧轮次摘要",List.of()));
        when(store.stage(eq(1L),eq(run),eq(initial),eq(1L),any(),eq(old.subList(0,2))))
                .thenReturn(staged);
        var result = graph().prepare(1,run,"第三个详细说",route,now.plusSeconds(90));
        var order = inOrder(model,store);
        order.verify(model).extract(any());
        order.verify(store).stage(eq(1L),eq(run),eq(initial),eq(1L),any(),eq(old.subList(0,2)));
        order.verify(store).activate(1,run,staged);
        var retained=new ArrayList<>(old.subList(2,4)); retained.addAll(recent);
        assertThat(result.recent().messages()).containsExactlyElementsOf(retained);
        assertThat(result.summaries()).singleElement().extracting(s->s.summary()).isEqualTo("旧轮次摘要");
    }

    @Test void pendingBatchIsResumedWithoutGeneratingMemoriesAgain() {
        var pending = new AgentCompactionStore.Batch(10,1,2,"可重试摘要",true);
        when(store.snapshot(1)).thenReturn(initial,
                new AgentCompactionStore.Snapshot(1,1,1,true,0,2,"可重试摘要"));
        when(store.pending(1,1,0)).thenReturn(pending);
        when(store.recent(1,1,3)).thenReturn(turns(8,3,false));
        when(store.messages(1,1,2,8,24)).thenReturn(List.of());
        graph().prepare(1,run,"继续",route,now.plusSeconds(90));
        verify(store).activate(1,run,pending);
        verifyNoInteractions(model);
    }

    @Test void disabledMemoryResumesPreviouslyEnabledBatchWithoutVectorService() {
        var pending = new AgentCompactionStore.Batch(10,1,2,"关闭记忆前已保存的摘要",true);
        when(store.snapshot(1)).thenReturn(
                new AgentCompactionStore.Snapshot(1,1,1,false,1,0,""),
                new AgentCompactionStore.Snapshot(1,1,1,false,1,2,"关闭记忆前已保存的摘要"));
        when(store.pending(1,1,0)).thenReturn(pending);
        var recent = turns(8,3,false);
        when(store.recent(1,1,3)).thenReturn(recent);
        when(store.messages(1,1,2,8,24)).thenReturn(List.of());

        var result = graph().prepare(1,run,"继续",route,now.plusSeconds(90));

        assertThat(result.summaries()).singleElement().extracting(s->s.summary())
                .isEqualTo("关闭记忆前已保存的摘要");
        assertThat(result.recent().messages()).containsExactlyElementsOf(recent);
        verify(store).activate(1,run,pending);
        verifyNoInteractions(model);
        verify(store,never()).stage(anyLong(),any(),any(),anyLong(),any(),any());
    }

    @Test @SuppressWarnings("unchecked")
    void manyShortTurnsAreBatchedUsingActualSerializedModelPrompt() {
        var all=turns(1,100,false);
        var covered=new java.util.concurrent.atomic.AtomicLong();
        when(store.snapshot(1)).thenAnswer(call->new AgentCompactionStore.Snapshot(1,1,0,false,0,
                covered.get(),covered.get()==0?"":"较早对话的工作摘要"));
        when(store.recent(1,1,3)).thenReturn(all.subList(194,200));
        when(store.messages(eq(1L),eq(1),anyLong(),eq(98L),eq(24))).thenAnswer(call->{
            long after=call.getArgument(2);
            return all.stream().filter(m->m.turnId()>after && m.turnId()<98).limit(48).toList();
        });
        when(store.stage(eq(1L),eq(run),any(),anyLong(),any(),any())).thenAnswer(call->
                new AgentCompactionStore.Batch(10,1,call.getArgument(3),"较早对话的工作摘要",false));
        doAnswer(call->{covered.set(call.<AgentCompactionStore.Batch>getArgument(2).throughTurnId()); return null;})
                .when(store).activate(eq(1L),eq(run),any());
        var settings=new AgentContextProperties(); settings.setPlatformWindowTokens(8192);
        var promptBudget=new AgentPromptBudget(settings);
        var executor=mock(cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor.class);
        when(executor.execute(any(),any(io.github.resilience4j.core.functions.CheckedSupplier.class)))
                .thenAnswer(call->call.<io.github.resilience4j.core.functions.CheckedSupplier<?>>getArgument(1).get());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var routed=new PreparedUserAiChat("test",UserAiFundingSource.PLATFORM,command->{
            assertThat(promptBudget.estimate(command.messages())).isLessThanOrEqualTo(5120);
            calls.incrementAndGet();
            return new cumt.zongzuo.community.ai.userprovider.UserAiRoutedResult(
                    new cumt.zongzuo.community.ai.provider.AiChatResult("{\"summary\":\"较早对话的工作摘要\",\"memories\":[]}",
                            "stop",100,20,"test","test"),UserAiFundingSource.PLATFORM);
        });
        var actualModel=new GatewayAgentCompactionModel(executor,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),promptBudget,
                new AgentMemorySafetyPolicy(),Clock.fixed(now,ZoneOffset.UTC),20_000);
        var graph=new AgentCompactionGraph(store,actualModel,promptBudget,new AgentMemorySafetyPolicy(),
                Clock.fixed(now,ZoneOffset.UTC),20_000);
        var prepared=graph.prepare(1,run,"继续",routed,now.plusSeconds(90));
        assertThat(calls.get()).isPositive();
        assertThat(prepared.recent().messages()).containsAll(all.subList(194,200));
        assertThat(prepared.recent().messages()).allMatch(m->m.turnId()>covered.get());
    }
    @Test void newCompactionOnlySummarizesAndNeverWaitsForVectorProjection() {
        when(model.fits(any())).thenReturn(true);
        var recent = turns(8,3,false);
        var old = turns(1,2,true);
        var staged = new AgentCompactionStore.Batch(10,1,1,"摘要",true);
        when(store.snapshot(1)).thenReturn(initial,
                new AgentCompactionStore.Snapshot(1,1,1,true,0,1,"摘要"));
        when(store.recent(1,1,3)).thenReturn(recent);
        when(store.messages(1,1,0,8,24)).thenReturn(old);
        when(store.messages(1,1,1,8,24)).thenReturn(old.subList(2,4));
        when(model.extract(any())).thenAnswer(call -> {
            AgentCompactionModel.Request request = call.getArgument(0);
            assertThat(request.extractMemories()).isFalse();
            return new AgentCompactionModel.Extraction("摘要",List.of());
        });
        when(store.stage(eq(1L),eq(run),eq(initial),eq(1L),any(),eq(old.subList(0,2))))
                .thenReturn(staged);
        var result = graph().prepare(1,run,"继续",route,now.plusSeconds(90));
        assertThat(result.summaries()).singleElement().extracting(s->s.summary()).isEqualTo("摘要");
        verify(store).activate(1,run,staged);
    }

    @Test void oldPendingBatchRecoversSummaryWithoutBlockingOnIndex() {
        var pending = new AgentCompactionStore.Batch(10,1,2,"可恢复摘要",true);
        when(store.snapshot(1)).thenReturn(initial,
                new AgentCompactionStore.Snapshot(1,1,1,true,0,2,"可恢复摘要"));
        when(store.pending(1,1,0)).thenReturn(pending);
        when(store.recent(1,1,3)).thenReturn(turns(8,3,false));
        when(store.messages(1,1,2,8,24)).thenReturn(List.of());
        var result = graph().prepare(1,run,"继续",route,now.plusSeconds(90));
        assertThat(result.summaries()).singleElement().extracting(s->s.summary()).isEqualTo("可恢复摘要");
        verify(store).activate(1,run,pending);
        verifyNoInteractions(model);
    }
    @Test void runtimeEnvelopeCanRequireCompactionBeforeTheLegacyBodyThreshold() {
        var recent = turns(8,3,false);
        var old = turns(1,1,false);
        var staged = new AgentCompactionStore.Batch(10,1,1,"较早任务摘要",true);
        when(model.fits(any())).thenReturn(true);
        when(store.snapshot(1)).thenReturn(initial,
                new AgentCompactionStore.Snapshot(1,1,1,true,0,1,"较早任务摘要"));
        when(store.recent(1,1,3)).thenReturn(recent);
        when(store.messages(1,1,0,8,24)).thenReturn(old);
        when(store.messages(1,1,1,8,24)).thenReturn(List.of());
        when(model.extract(any())).thenReturn(new AgentCompactionModel.Extraction("较早任务摘要",List.of()));
        when(store.stage(eq(1L),eq(run),eq(initial),eq(1L),any(),eq(old))).thenReturn(staged);
        // 模拟工具schema和JSON元信息挤占剩余预算，原文长度本身远低于70%水位。
        var result = graph().prepare(1,run,"继续",route,now.plusSeconds(90),
                prepared -> prepared.recent().messages().size() <= 6);
        assertThat(result.recent().messages()).containsExactlyElementsOf(recent);
        assertThat(result.summaries()).singleElement().extracting(v->v.summary()).isEqualTo("较早任务摘要");
        verify(store).activate(1,run,staged);
    }
}
