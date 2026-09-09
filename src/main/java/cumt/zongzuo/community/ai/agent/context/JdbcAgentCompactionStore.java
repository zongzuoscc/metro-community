package cumt.zongzuo.community.ai.agent.context;

import cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit;
import cumt.zongzuo.community.ai.agent.memory.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.ByteBuffer;
import java.sql.Statement;
import java.util.*;

/**
 * 压缩结果和记忆事实同一 MySQL 本地事务提交；Milvus 不在这个事务里，也没有伪装的跨库回滚。
 * 唯一前缀键保证重复请求重用批次，READY 才是工作上下文水位。旧摘要与原消息均保留用于追溯。
 */
public final class JdbcAgentCompactionStore implements AgentCompactionStore {
    private final JdbcTemplate jdbc;
    private final AgentMemoryMapper memories;
    private final AgentMemorySafetyPolicy safety;
    private final TransactionTemplate transactions;
    private final boolean memoryFeatureEnabled;

    public JdbcAgentCompactionStore(JdbcTemplate jdbc,AgentMemoryMapper memories,AgentMemorySafetyPolicy safety,
                                    PlatformTransactionManager manager,boolean memoryFeatureEnabled) {
        this.jdbc=jdbc; this.memories=memories; this.safety=safety;
        this.transactions=new TransactionTemplate(manager); this.transactions.setTimeout(5);
        this.memoryFeatureEnabled=memoryFeatureEnabled;
    }
    @Override public Snapshot snapshot(long userId) {
        memories.ensureSetting(userId);
        var rows=jdbc.query("""
                SELECT c.id,c.context_start_episode_no,c.memory_epoch,s.enabled,s.lock_version
                FROM agent_conversation c JOIN agent_memory_setting s ON s.user_id=c.user_id
                JOIN sys_user u ON u.id=c.user_id
                WHERE c.user_id=? AND u.account_state='ACTIVE'
                """,(rs,n)->new Snapshot(rs.getLong(1),rs.getInt(2),rs.getLong(3),
                memoryFeatureEnabled && rs.getBoolean(4),rs.getLong(5),0,""),userId);
        if (rows.isEmpty()) throw new IllegalStateException("Active conversation is unavailable");
        var s=rows.getFirst();
        var checkpoints=jdbc.query("""
                SELECT through_turn_id,summary_text FROM agent_context_compaction
                WHERE user_id=? AND context_boundary=? AND state='READY'
                ORDER BY through_turn_id DESC LIMIT 1
                """,(rs,n)->new Snapshot(s.conversationId(),s.boundary(),s.memoryEpoch(),s.memoryEnabled(),
                s.settingVersion(),rs.getLong(1),rs.getString(2)),userId,s.boundary());
        return checkpoints.isEmpty()?s:checkpoints.getFirst();
    }
    @Override public Batch pending(long userId,int boundary,long afterTurnId) {
        var rows=jdbc.query("""
                SELECT id,context_boundary,through_turn_id,summary_text,memory_enabled
                FROM agent_context_compaction WHERE user_id=? AND context_boundary=?
                AND previous_turn_id=? AND state='PENDING'
                """,(rs,n)->new Batch(rs.getLong(1),rs.getInt(2),rs.getLong(3),rs.getString(4),rs.getBoolean(5)),
                userId,boundary,afterTurnId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    @Override public List<AgentConversationHistoryHit> recent(long userId,int boundary,int turns) {
        return messagesQuery(userId,boundary,0,Long.MAX_VALUE,turns,true);
    }
    @Override public List<AgentConversationHistoryHit> messages(long userId,int boundary,long after,long before,int turns) {
        return messagesQuery(userId,boundary,after,before,turns,false);
    }
    private List<AgentConversationHistoryHit> messagesQuery(long userId,int boundary,long after,long before,int turns,boolean newest) {
        if (turns<1 || turns>64) throw new IllegalArgumentException("Invalid compaction page size");
        // 唯一动态 SQL 是内部布尔值决定的 ASC/DESC；所有用户输入仍使用绑定参数。
        String sql="""
                SELECT m.id,m.turn_id,m.user_id,m.role,m.content,m.created_at FROM agent_message m
                JOIN (SELECT t.id,t.user_id FROM agent_turn t
                  JOIN agent_episode e ON e.id=t.episode_id AND e.user_id=t.user_id
                  JOIN agent_conversation c ON c.id=t.conversation_id AND c.user_id=t.user_id
                  WHERE t.user_id=? AND t.state='SUCCEEDED' AND t.id>? AND t.id<?
                    AND c.context_start_episode_no=? AND e.episode_no>=c.context_start_episode_no
                    AND EXISTS (SELECT 1 FROM agent_message u WHERE u.turn_id=t.id AND u.user_id=t.user_id
                      AND u.role='USER' AND u.state='FINAL')
                    AND EXISTS (SELECT 1 FROM agent_message a WHERE a.turn_id=t.id AND a.user_id=t.user_id
                      AND a.role='ASSISTANT' AND a.state='FINAL')
                  ORDER BY t.id %s LIMIT ?) selected ON selected.id=m.turn_id AND selected.user_id=m.user_id
                WHERE m.user_id=? AND m.state='FINAL' AND m.role IN ('USER','ASSISTANT')
                ORDER BY m.turn_id ASC,m.id ASC
                """.formatted(newest?"DESC":"ASC");
        return jdbc.query(sql,(rs,n)->new AgentConversationHistoryHit(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                rs.getString(4),rs.getString(5),rs.getTimestamp(6).toLocalDateTime()),userId,after,before,boundary,turns,userId);
    }

    @Override public Batch stage(long userId,UUID runId,Snapshot expected,long through,
                                 AgentCompactionModel.Extraction extraction,List<AgentConversationHistoryHit> evidence) {
        validate(extraction,evidence,userId,expected.memoryEnabled());
        if (through<=expected.coveredThroughTurnId()) throw new IllegalArgumentException("Invalid compaction prefix");
        return transactions.execute(status -> {
            lockRunAndConversation(userId,runId,expected.boundary());
            var current=snapshot(userId);
            // 模型运行期间用户改了记忆/开关或另一个任务已经推进，不能用过期快照提交新事实。
            if (!current.equals(expected)) throw new IllegalStateException("Compaction input snapshot changed");
            Batch existing=pending(userId,expected.boundary(),expected.coveredThroughTurnId());
            if (existing!=null) return existing;
            var key=new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var ps=connection.prepareStatement("""
                        INSERT INTO agent_context_compaction(user_id,conversation_id,context_boundary,
                          previous_turn_id,through_turn_id,summary_text,memory_enabled,state,created_at)
                        VALUES (?,?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP(6))
                        """,Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1,userId); ps.setLong(2,expected.conversationId()); ps.setInt(3,expected.boundary());
                ps.setLong(4,expected.coveredThroughTurnId()); ps.setLong(5,through);
                ps.setString(6,extraction.summary()); ps.setBoolean(7,expected.memoryEnabled()); return ps;
            },key);
            boolean inserted=false;
            for (var fact:extraction.memories()) {
                String normalized=AgentMemoryText.normalize(fact.content());
                String hash=AgentMemoryText.sha256(normalized);
                // 包括已删除版本的去重记录，不能因重放旧压缩批次恢复用户已删除的同一条记忆。
                if (memories.contentHashCount(userId,hash)>0) continue;
                var source=evidence.stream().filter(m->m.messageId()==fact.sourceMessageId()).findFirst().orElseThrow();
                var item=new AgentMemoryMapper.MemoryInsert(); item.userId=userId; item.category=fact.category();
                memories.insertItem(item);
                var version=new AgentMemoryMapper.MemoryVersionInsert(); version.userId=userId; version.memoryId=item.id;
                version.versionNo=1; version.content=fact.content(); version.normalizedContent=normalized; version.contentHash=hash;
                memories.insertVersion(version);
                if (memories.activateVersion(item.id,userId,version.id)!=1) throw new IllegalStateException("Memory owner changed");
                memories.insertSource(userId,item.id,version.id,source.turnId(),source.messageId());
                memories.insertProjection(version.id,userId); inserted=true;
            }
            if (inserted) memories.incrementEpoch(userId);
            return new Batch(Objects.requireNonNull(key.getKey()).longValue(),expected.boundary(),through,
                    extraction.summary(),expected.memoryEnabled());
        });
    }
    @Override public void activate(long userId,UUID runId,Batch batch) {
        transactions.executeWithoutResult(status -> {
            lockRunAndConversation(userId,runId,batch.boundary());
            int changed=jdbc.update("""
                    UPDATE agent_context_compaction b JOIN agent_conversation c ON c.user_id=b.user_id
                    SET b.state='READY',b.ready_at=CURRENT_TIMESTAMP(6)
                    WHERE b.id=? AND b.user_id=? AND b.state='PENDING'
                      AND b.context_boundary=c.context_start_episode_no
                    """,batch.id(),userId);
            if (changed!=1) throw new IllegalStateException("Compaction activation fence changed");
        });
    }
    private void lockRunAndConversation(long userId,UUID runId,int boundary) {
        // 与注销编排一致先锁账号，再按 guard → conversation → setting 固定顺序加锁。
        var accounts=jdbc.queryForList("SELECT id FROM sys_user WHERE id=? AND account_state='ACTIVE' FOR UPDATE",Long.class,userId);
        byte[] uuid=ByteBuffer.allocate(16).putLong(runId.getMostSignificantBits()).putLong(runId.getLeastSignificantBits()).array();
        var guards=jdbc.queryForList("""
                SELECT user_id FROM agent_run_guard WHERE user_id=? AND active_run_id=?
                  AND active_run_type='PERSISTENT' AND lease_until>CURRENT_TIMESTAMP(6) FOR UPDATE
                """,Long.class,userId,uuid);
        var conversations=jdbc.queryForList("""
                SELECT id FROM agent_conversation WHERE user_id=? AND context_start_episode_no=? FOR UPDATE
                """,Long.class,userId,boundary);
        jdbc.queryForList("SELECT user_id FROM agent_memory_setting WHERE user_id=? FOR UPDATE",Long.class,userId);
        if (accounts.size()!=1 || guards.size()!=1 || conversations.size()!=1)
            throw new IllegalStateException("Compaction run or conversation fence lost");
    }
    private void validate(AgentCompactionModel.Extraction extraction,List<AgentConversationHistoryHit> evidence,
                          long userId,boolean enabled) {
        if (extraction.summary().length()>4000 || !safety.canUseAsContext(extraction.summary())
                || extraction.memories().size()>12 || (!enabled && !extraction.memories().isEmpty()))
            throw new IllegalStateException("Invalid compaction result");
        for (var fact:extraction.memories()) {
            if (!Set.of("PREFERENCE","GOAL","PROFILE").contains(fact.category()) || !safety.canStore(fact.content())
                    || fact.quote().codePointCount(0,fact.quote().length())<4 || !safety.canStore(fact.quote())
                    || evidence.stream().noneMatch(m->m.userId()==userId && "USER".equals(m.role())
                    && m.messageId()==fact.sourceMessageId() && m.content().contains(fact.quote())))
                throw new IllegalStateException("Invalid memory source evidence");
        }
    }
}
