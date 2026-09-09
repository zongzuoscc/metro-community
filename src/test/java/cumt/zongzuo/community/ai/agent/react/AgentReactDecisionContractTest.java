package cumt.zongzuo.community.ai.agent.react;

import cumt.zongzuo.community.ai.agent.planner.AgentReadOnlyTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentReactDecisionContractTest {

    @Test
    void toolCallStripsOuterWhitespaceButKeepsTheDynamicQuery() {
        AgentToolCall call = new AgentToolCall(AgentReadOnlyTool.COMMUNITY_ARTICLES,
                "  Spring\tAI\n　检索  ");

        assertThat(call.tool()).isEqualTo(AgentReadOnlyTool.COMMUNITY_ARTICLES);
        assertThat(call.query()).isEqualTo("Spring AI 检索");
    }

    @Test
    void toolCallRejectsBlankAndMoreThanTwoThousandCodePoints() {
        assertThatThrownBy(() -> new AgentToolCall(AgentReadOnlyTool.WEB_SEARCH, " \n\t "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentToolCall(AgentReadOnlyTool.WEB_SEARCH,
                "😀".repeat(2_001)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new AgentToolCall(AgentReadOnlyTool.WEB_SEARCH,
                "😀".repeat(2_000)).query().codePointCount(0, 4_000)).isEqualTo(2_000);
    }

    @Test
    void nullCallRepresentsAnExplicitFinishDecision() {
        assertThat(new AgentReactDecision(null).call()).isNull();
    }
}
