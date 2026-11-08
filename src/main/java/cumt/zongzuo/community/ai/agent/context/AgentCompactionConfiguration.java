package cumt.zongzuo.community.ai.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.memory.*;
import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.time.Clock;

/** 启用持久 Agent 就启用回答前压缩；必要压缩只保存摘要，长期记忆由 Agent 工具主动管理。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"metro.ai.enabled","metro.ai.agent.enabled"},havingValue="true")
public class AgentCompactionConfiguration {
    @Bean AgentCompactionStore agentCompactionStore(DataSource dataSource,AgentMemoryMapper memories,
                                                   AgentMemorySafetyPolicy safety,PlatformTransactionManager manager,
                                                   MetroAiProperties properties) {
        // 独立 JdbcTemplate 的语句超时，不改变业务模块共用模板；底层仍加入相同 DataSource 本地事务。
        var jdbc=new JdbcTemplate(dataSource); jdbc.setQueryTimeout(3);
        return new JdbcAgentCompactionStore(jdbc,memories,safety,manager,properties.getMemory().isEnabled());
    }
    @Bean AgentCompactionGraph agentCompactionGraph(AgentCompactionStore store,AiCapabilityExecutor executor,
                                                   ObjectMapper json,AgentContextProperties contexts,
                                                   AgentMemorySafetyPolicy safety,Clock clock,MetroAiProperties properties) {
        var budget=new AgentPromptBudget(contexts);
        var model=new GatewayAgentCompactionModel(executor,json,budget,safety,clock,properties.getAgent().getMaxInputCharacters());
        return new AgentCompactionGraph(store,model,budget,safety,clock,properties.getAgent().getMaxInputCharacters());
    }
}
