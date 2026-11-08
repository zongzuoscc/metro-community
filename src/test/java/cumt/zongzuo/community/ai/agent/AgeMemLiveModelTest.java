package cumt.zongzuo.community.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.context.*;
import cumt.zongzuo.community.ai.agent.memory.*;
import cumt.zongzuo.community.ai.agent.memory.index.*;
import cumt.zongzuo.community.ai.agent.react.NativeAgentRuntime;
import cumt.zongzuo.community.ai.agent.retrieval.*;
import cumt.zongzuo.community.ai.provider.*;
import cumt.zongzuo.community.ai.userprovider.*;
import io.milvus.v2.client.*;
import io.milvus.v2.service.collection.request.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式启用：真实聊天模型、embedding、MySQL、Milvus；站内文章检索替身，不是HTTP/SSE全链路验收。 */
@EnabledIfEnvironmentVariable(named = "AGEMEM_LIVE_MODEL", matches = "true")
class AgeMemLiveModelTest {
    @Test
    @SuppressWarnings("unchecked")
    void actualModelPersistsMemoryThenWorkerProjectsItForSemanticRecall() throws Exception {
        var db = new AgeMemLiveDatabaseSupport();
        new ResourceDatabasePopulator(new FileSystemResource(
                "docs/database/migrations/2026-09-08-agent-context-window.sql"), new FileSystemResource(
                "docs/database/migrations/2026-09-09-agent-context-compaction.sql"))
                .execute(Objects.requireNonNull(db.jdbc.getDataSource()));
        UUID runId = UUID.randomUUID();
        db.seed(runId);
        String question = "请记住：我学习 Java 后端时偏好先看最小可运行示例，再看原理。请保存这条学习偏好，然后简单确认。";
        db.setQuestion(question);
        Clock clock = Clock.systemUTC();
        String model = required("METRO_AI_PLATFORM_MODEL");
        String embeddingModel = required("METRO_AI_EMBEDDING_MODEL");
        var gateway = new OpenAiCompatibleAiChatGateway(Map.of(AiCapability.AGENT,
                OpenAiCompatibleAiChatGateway.httpTransport(Duration.ofSeconds(10), Duration.ofSeconds(60))),
                required("METRO_AI_PLATFORM_BASE_URL"), required("METRO_AI_PLATFORM_API_KEY"),
                required("METRO_AI_PLATFORM_PROVIDER"), model, 2048);
        var embedding = new OpenAiCompatibleEmbeddingGateway(
                OpenAiCompatibleEmbeddingGateway.httpTransport(Duration.ofSeconds(10), Duration.ofSeconds(45)),
                required("METRO_AI_PLATFORM_BASE_URL"), required("METRO_AI_PLATFORM_API_KEY"),
                required("METRO_AI_PLATFORM_PROVIDER"), embeddingModel, 1024);
        var client = new MilvusClientV2(ConnectConfig.builder().uri(required("AGEMEM_LIVE_MILVUS_URI"))
                .username(required("AGEMEM_LIVE_MILVUS_USER")).password(required("AGEMEM_LIVE_MILVUS_PASSWORD"))
                .connectTimeoutMs(10_000L).rpcDeadlineMs(60_000L).build());
        String collection = "agemem_model_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            var executor = new NativeReActToolTest.DirectExecutor();
            var safety = new AgentMemorySafetyPolicy();
            var repository = new SdkMemoryVectorRepository(client, collection, true, clock);
            var vectors = new AgentMemoryVectorService(db.mapper, repository, executor, embedding, safety,
                    clock, Duration.ofSeconds(45), embeddingModel, 32);
            ObjectProvider<AgentMemoryVectorService> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(vectors);
            var recall = new AgentMemoryRecallService(db.mapper, provider);
            var retrieval = mock(HybridArticleRetrievalService.class);
            when(retrieval.retrieve(any())).thenReturn(new ArticleRetrievalResult(0,0,true,true,List.of(),List.of()));
            AtomicInteger nativeCalls = new AtomicInteger();
            var route = new PreparedUserAiChat(model, UserAiFundingSource.PLATFORM,
                    command -> new UserAiRoutedResult(gateway.generate(command), UserAiFundingSource.PLATFORM),
                    () -> {},
                    (command, observer) -> new UserAiRoutedResult(gateway.stream(command, observer), UserAiFundingSource.PLATFORM),
                    prompt -> { nativeCalls.incrementAndGet(); return gateway.call(prompt); });
            UserAiChatRouter router = new UserAiChatRouter() {
                public UserAiRoutedResult generate(long userId, AiChatCommand command) { return route.generate(command); }
                public PreparedUserAiChat prepare(long userId, String expected) { return route; }
            };
            var budget = new AgentPromptBudget(new AgentContextProperties());
            var json = new ObjectMapper().findAndRegisterModules();
            var compaction = new AgentCompactionGraph(
                    new JdbcAgentCompactionStore(db.jdbc, db.mapper, safety, db.transactions, true),
                    new GatewayAgentCompactionModel(executor,json,budget,safety,clock,200_000),
                    budget,safety,clock,200_000);
            var service = new GroundedAnswerService(retrieval,executor,router,new GroundedAnswerParser(json),
                    clock,model,Duration.ofSeconds(60),recall,null,true,null,
                    new NativeAgentRuntime(executor,json,clock,Duration.ofSeconds(60),8,10,budget,200_000),
                    budget,200_000,compaction,db.tools);
            var deltas = new StringBuilder();
            var answer = service.answerPersistent(9,runId,question,false,clock.instant().plusSeconds(180),
                    () -> true,deltas::append);
            assertThat(answer.answer()).isNotBlank();
            assertThat(deltas).isNotEmpty();
            assertThat(nativeCalls.get()).isGreaterThanOrEqualTo(2);
            var saved = db.management.list(9);
            assertThat(saved).isNotEmpty();
            assertThat(saved).anySatisfy(memory -> assertThat(memory.content()).contains("示例"));
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_source WHERE user_id=9",Integer.class))
                    .isPositive();
            assertThat(vectors.indexStatus(9)).isEqualTo(AgentMemoryVectorService.IndexStatus.PENDING);
            // 明确在回答已完成后才执行投影，验证正文成功不依赖索引可用。
            new AgentMemoryVectorProjectionTask(db.mapper,vectors,clock,Duration.ofSeconds(90),embeddingModel,32)
                    .projectPending();
            assertThat(vectors.indexStatus(9)).isEqualTo(AgentMemoryVectorService.IndexStatus.CURRENT);
            assertThat(vectors.recall(9,"我希望怎样学习服务端编程？",6,clock.instant().plusSeconds(60)))
                    .anySatisfy(memory -> assertThat(memory.content()).contains("示例"));
        } finally {
            try {
                if (client.hasCollection(HasCollectionReq.builder().collectionName(collection).build()))
                    client.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
            } finally { client.close(); }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing live test setting: " + name);
        return value;
    }
}
