package cumt.zongzuo.community.ai.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit;
import cumt.zongzuo.community.ai.agent.memory.AgentMemorySafetyPolicy;
import cumt.zongzuo.community.ai.provider.*;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.userprovider.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GatewayAgentCompactionModelTest {
    GatewayAgentCompactionModel model() {
        return new GatewayAgentCompactionModel(mock(AiCapabilityExecutor.class),new ObjectMapper(),
                new AgentPromptBudget(new AgentContextProperties()),new AgentMemorySafetyPolicy(),Clock.systemUTC(),400_000);
    }
    AgentCompactionModel.Request request(boolean memory) {
        return new AgentCompactionModel.Request(1,"request","",List.of(
                new AgentConversationHistoryHit(10,5,1,"USER","选外套时我偏向于黑色，日常我喜欢蓝色。",LocalDateTime.now()),
                new AgentConversationHistoryHit(11,5,1,"ASSISTANT","你应该喜欢红色。",LocalDateTime.now())),memory,
                new PreparedUserAiChat("qwen",UserAiFundingSource.PLATFORM,
                        command -> { throw new AssertionError("Parser fixture cannot invoke model"); }),
                Instant.now().plusSeconds(30));
    }
    @Test void allowsSemanticExtractionWithoutPreferencePrefixAndKeepsScope() {
        var result=model().parse("""
                {"summary":"讨论穿衣偏好","memories":[
                  {"category":"PREFERENCE","content":"用户选外套偏向黑色","sourceMessageId":10,"quote":"选外套时我偏向于黑色"},
                  {"category":"PREFERENCE","content":"用户日常喜欢蓝色","sourceMessageId":10,"quote":"日常我喜欢蓝色"}]}
                """,request(true));
        assertThat(result.memories()).hasSize(2);
    }
    @Test void rejectsAssistantAsMemorySource() {
        assertThatThrownBy(()->model().parse("""
                {"summary":"摘要","memories":[{"category":"PREFERENCE","content":"喜欢红色","sourceMessageId":11,"quote":"你应该喜欢红色"}]}
                """,request(true))).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsInventedQuotesAndMemoryWhenDisabled() {
        String response="""
                {"summary":"摘要","memories":[{"category":"PREFERENCE","content":"喜欢黑色","sourceMessageId":10,"quote":"喜欢黑色"}]}
                """;
        assertThatThrownBy(()->model().parse(response,request(true))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->model().parse(response,request(false))).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsSensitiveSummaryAndOversizedOrMalformedResults() {
        assertThatThrownBy(()->model().parse("{\"summary\":\"password=abcd123456\",\"memories\":[]}",request(true)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->model().parse("not JSON",request(true))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->model().parse("{\"summary\":\"\",\"memories\":[]}",request(true)))
                .isInstanceOf(IllegalStateException.class);
    }
}
