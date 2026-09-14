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
                .isEqualTo(new AgentPromptBudget.Limits(994880, 4096));
    }

    /** 百万工作窗口仍服从具体模型容量，不能让较小的模型收到超限输入。 */
    @Test
    void explicitPlatformCapacityTakesPriorityOverMillionTokenDefault() {
        var properties = new AgentContextProperties();
        properties.getModelWindows().put("small-platform", 32768);
        assertThat(new AgentPromptBudget(properties).limits("small-platform", UserAiFundingSource.PLATFORM))
                .isEqualTo(new AgentPromptBudget.Limits(27648, 4096));
    }

    /** 超大模型也受到业务工作窗口约束；扩大默认值不能意外取消窗口上限。 */
    @Test
    void largerModelIsCappedAtMillionTokenWorkingWindow() {
        var properties = new AgentContextProperties();
        properties.getModelWindows().put("larger-model", 2000000);
        assertThat(new AgentPromptBudget(properties).limits("larger-model", UserAiFundingSource.USER))
                .isEqualTo(new AgentPromptBudget.Limits(994880, 4096));
    }

    /** 环境配置显式写入百万窗口时也必须通过启动校验，而不只是在字段默认值中生效。 */
    @Test
    void explicitlyConfiguredMillionTokenWindowIsAccepted() {
        var properties = new AgentContextProperties();
        properties.setWorkingWindowTokens(1000000);
        properties.setPlatformWindowTokens(1000000);
        assertThat(new AgentPromptBudget(properties).limits("platform", UserAiFundingSource.PLATFORM))
                .isEqualTo(new AgentPromptBudget.Limits(994880, 4096));
        properties.setWorkingWindowTokens(1000001);
        assertThatThrownBy(() -> new AgentPromptBudget(properties))
                .isInstanceOf(IllegalArgumentException.class);
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
