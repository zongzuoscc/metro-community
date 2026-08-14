package cumt.zongzuo.community.ai.agent.history;

import cumt.zongzuo.community.ai.agent.turn.AgentTurnAdmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Worker 周期领取一个封存片段并生成摘要；外部模型调用始终发生在数据库事务之外。 */
@Service
@ConditionalOnProperty(name = "metro.ai.memory.summary-enabled", havingValue = "true")
public class AgentEpisodeSummaryTask {

    private static final Logger log = LoggerFactory.getLogger(AgentEpisodeSummaryTask.class);

    private final AgentEpisodeSummaryMapper mapper;
    private final AgentEpisodeSummaryGenerator generator;
    private final TransactionTemplate transactions;

    public AgentEpisodeSummaryTask(AgentEpisodeSummaryMapper mapper,
                                   AgentEpisodeSummaryGenerator generator,
                                   PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.generator = generator;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${metro.ai.memory.summary-delay-ms:15000}",
            initialDelayString = "${metro.ai.memory.summary-initial-delay-ms:15000}")
    public void summarizeNext() {
        AgentEpisodeSummaryClaim claim = transactions.execute(status -> {
            AgentEpisodeSummaryClaim candidate = mapper.selectNextForUpdate();
            if (candidate == null || mapper.claim(candidate.episodeId(), candidate.userId(),
                    candidate.state()) != 1) return null;
            return candidate;
        });
        if (claim == null) return;
        try {
            String summary = generator.generate(claim.userId(), claim.episodeId(),
                    mapper.messages(claim.episodeId(), claim.userId()));
            if (mapper.complete(claim.episodeId(), claim.userId(), summary,
                    AgentTurnAdmissionService.sha256(summary)) != 1) {
                throw new IllegalStateException("Episode summary completion fence was lost");
            }
        } catch (RuntimeException error) {
            mapper.fail(claim.episodeId(), claim.userId());
            // 日志只记录 ID 和异常类型，不能把原始对话或生成摘要写进运维日志。
            log.warn("Agent episode summary failed episodeId={} exceptionType={}",
                    claim.episodeId(), error.getClass().getName());
        }
    }
}
