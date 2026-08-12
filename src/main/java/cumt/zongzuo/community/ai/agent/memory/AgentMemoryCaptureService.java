package cumt.zongzuo.community.ai.agent.memory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 持久 turn 成功完成后，使用确定性规则提取低风险用户事实。
 *
 * <p>捕获只发生在持久路径；临时 turn 不会创建 agent_message，也不会调用本服务。
 * 分类器仅接受明确的偏好、目标和基础资料，敏感内容由安全策略先行拒绝。</p>
 */
@Service
public class AgentMemoryCaptureService {

    private static final Pattern PREFERENCE = Pattern.compile(
            "^(?:\u8bf7\u8bb0\u4f4f)?\s*(\u6211(?:\u559c\u6b22|\u504f\u597d|\u4e60\u60ef|\u4e0d\u559c\u6b22).{1,960})$");
    private static final Pattern GOAL = Pattern.compile(
            "^(?:\u8bf7\u8bb0\u4f4f)?\s*(\u6211(?:\u7684)?(?:\u76ee\u6807\u662f|\u8ba1\u5212|\u60f3\u8981|\u6b63\u5728).{1,960})$");
    private static final Pattern PROFILE = Pattern.compile(
            "^(?:\u8bf7\u8bb0\u4f4f)?\s*(\u6211(?:\u662f|\u4ece\u4e8b|\u4f4f\u5728|\u6765\u81ea).{1,960})$");

    private final AgentMemoryMapper mapper;
    private final TransactionTemplate transactions;
    private final AgentMemorySafetyPolicy safety;

    public AgentMemoryCaptureService(AgentMemoryMapper mapper,
                                     PlatformTransactionManager transactionManager,
                                     AgentMemorySafetyPolicy safety) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        // 当回答完成事务已存在时，记忆捕获使用数据库保存点。捕获失败可以单独回滚，
        // 而成功捕获会与 turn 的 SUCCEEDED 状态在同一外层事务中一起对外可见。
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.safety = safety;
    }

    /**
     * 从指定 turn 的 USER 消息中最多捕获一条当前用户记忆。
     * 同时使用 source_message_id 和归一化 content_hash 去重，保证异步重试不会创建重复记忆。
     */
    public int captureUserMessage(long userId, long turnId) {
        Integer captured = transactions.execute(status -> captureInTransaction(userId, turnId));
        return captured == null ? 0 : captured;
    }

    private int captureInTransaction(long userId, long turnId) {
        mapper.ensureSetting(userId);
        if (!Boolean.TRUE.equals(mapper.enabled(userId))) {
            return 0;
        }
        Long messageId = mapper.sourceMessageId(turnId, userId);
        if (messageId == null || mapper.sourceCount(messageId, userId) > 0) {
            return 0;
        }
        Candidate candidate = classify(mapper.messageContent(messageId, userId), safety);
        if (candidate == null) {
            return 0;
        }
        AgentMemoryMapper.MemoryInsert item = new AgentMemoryMapper.MemoryInsert();
        String normalized = normalize(candidate.content());
        String contentHash = sha256(normalized);
        if (mapper.contentHashCount(userId, contentHash) > 0) {
            return 0;
        }
        item.userId = userId;
        item.category = candidate.category();
        mapper.insertItem(item);
        AgentMemoryMapper.MemoryVersionInsert version = new AgentMemoryMapper.MemoryVersionInsert();
        version.userId = userId;
        version.memoryId = item.id;
        version.versionNo = 1;
        version.content = candidate.content();
        version.normalizedContent = normalized;
        version.contentHash = contentHash;
        mapper.insertVersion(version);
        if (mapper.activateVersion(item.id, userId, version.id) != 1) {
            throw new IllegalStateException("Memory activation lost its owner binding");
        }
        mapper.insertSource(userId, item.id, version.id, turnId, messageId);
        mapper.insertProjection(version.id, userId);
        mapper.incrementEpoch(userId);
        return 1;
    }

    private static Candidate classify(String input, AgentMemorySafetyPolicy safety) {
        if (input == null) return null;
        String text = input.strip();
        if (!safety.canStore(text)) {
            return null;
        }
        for (var rule : java.util.List.of(
                new Rule("PREFERENCE", PREFERENCE), new Rule("GOAL", GOAL),
                new Rule("PROFILE", PROFILE))) {
            var match = rule.pattern().matcher(text);
            if (match.matches()) {
                return new Candidate(rule.category(), match.group(1).strip());
            }
        }
        return null;
    }

    static String normalize(String value) {
        return value.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private record Rule(String category, Pattern pattern) {}
    private record Candidate(String category, String content) {}
}
