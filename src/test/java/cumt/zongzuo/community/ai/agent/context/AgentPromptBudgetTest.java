package cumt.zongzuo.community.ai.agent.context;

import cumt.zongzuo.community.ai.provider.*;
import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AgentPromptBudgetTest {
    @Test
    void unknownUserModelAndConfiguredModelHaveDifferentSafeBudgets() {
        var properties = new AgentContextProperties();
        properties.getModelWindows().put("small-model", 4096);
        var budget = new AgentPromptBudget(properties);
        assertThat(budget.limits("unknown", UserAiFundingSource.USER))
                .isEqualTo(new AgentPromptBudget.Limits(5120, 2048));
        assertThat(budget.limits("small-model", UserAiFundingSource.USER))
                .isEqualTo(new AgentPromptBudget.Limits(2560, 1024));
        assertThat(budget.limits("platform", UserAiFundingSource.PLATFORM))
                .isEqualTo(new AgentPromptBudget.Limits(27648, 4096));
    }

    @Test
    void confirmedLargeModelIsNotCappedAtThirtyTwoKByDefault() {
        var properties = new AgentContextProperties();
        properties.getModelWindows().put("confirmed-large", 131072);
        var budget = new AgentPromptBudget(properties);
        assertThat(budget.limits("confirmed-large", UserAiFundingSource.USER))
                .isEqualTo(new AgentPromptBudget.Limits(125952, 4096));
    }

    @Test
    void countsChineseCodeAndProtocolOverheadWithoutTreatingCharactersAsTokens() {
        var budget = new AgentPromptBudget(new AgentContextProperties());
        var shortPrompt = List.of(new AiPromptMessage(AiPromptRole.USER, "事务"));
        var longPrompt = List.of(new AiPromptMessage(AiPromptRole.SYSTEM, "安全约束"),
                new AiPromptMessage(AiPromptRole.USER, "事务与并发🙂 SELECT FOR UPDATE;".repeat(100)));
        assertThat(budget.estimate(shortPrompt)).isGreaterThan(2);
        assertThat(budget.estimate(longPrompt)).isGreaterThan(1000);
    }
}
