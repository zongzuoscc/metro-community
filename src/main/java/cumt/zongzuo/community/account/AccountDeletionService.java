package cumt.zongzuo.community.account;

import cumt.zongzuo.community.service.UserService;
import cumt.zongzuo.community.recommendation.service.RecommendationProfileService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 账号注销编排：先进入七天反悔期，到期后保留用户主键和公共内容归属，
 * 仅逻辑删除账号并脱敏私人数据。已发布文章、公开评论、举报和审核记录属于
 * 社区事实或安全审计，继续保留用户主键作为不可变归属；私信、草稿、收藏、
 * 关注备注、推荐行为、Agent 上下文和自带 API 密钥则在反悔期结束后清除。
 */
@Service
public class AccountDeletionService {

    public static final String CONFIRMATION = "DELETE_MY_ACCOUNT";

    private final AccountDeletionMapper mapper;
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final RecommendationProfileService recommendationProfiles;

    public AccountDeletionService(AccountDeletionMapper mapper, JdbcTemplate jdbc, UserService users,
                                  RecommendationProfileService recommendationProfiles) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.users = users;
        this.recommendationProfiles = recommendationProfiles;
    }

    public AccountDeletionStatus status(long userId) {
        AccountDeletionRecord current = requireRecord(mapper.select(userId));
        return current.status();
    }

    @Transactional
    public AccountDeletionStatus request(long userId, String confirmation) {
        if (!CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException("请输入 DELETE_MY_ACCOUNT 以确认注销");
        }
        AccountDeletionRecord current = requireRecord(mapper.selectForUpdate(userId));
        if (current.state() == AccountState.PENDING_DELETE) {
            return current.status();
        }
        if (current.state() != AccountState.ACTIVE || mapper.request(userId, current.version()) != 1) {
            throw new IllegalStateException("账号注销状态已经发生变化，请刷新后重试");
        }
        invalidateUserCacheAfterCommit(userId);
        return requireRecord(mapper.selectForUpdate(userId)).status();
    }

    @Transactional
    public AccountDeletionStatus restore(long userId) {
        AccountDeletionRecord current = requireRecord(mapper.selectForUpdate(userId));
        if (current.state() == AccountState.ACTIVE) {
            return current.status();
        }
        if (current.state() != AccountState.PENDING_DELETE
                || mapper.restore(userId, current.version()) != 1) {
            throw new IllegalStateException("七天恢复期限已结束，账号无法恢复");
        }
        invalidateUserCacheAfterCommit(userId);
        return requireRecord(mapper.selectForUpdate(userId)).status();
    }

    /**
     * Worker 每次只锁定有限数量的到期账号，避免一次清理长时间占用数据库连接。
     * 同一事务内先脱敏私人事实，再把账号行推进到 DELETED；任何一步失败都会整体回滚。
     */
    @Transactional
    public int purgeDue(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("账号清理批量大小必须在 1 到 200 之间");
        }
        List<Long> due = mapper.selectDueForUpdate(limit);
        int completed = 0;
        for (Long userId : due) {
            AccountDeletionRecord current = requireRecord(mapper.selectForUpdate(userId));
            scrubPrivateData(userId);
            if (mapper.finalizeDeletion(userId, current.version()) != 1) {
                throw new IllegalStateException("账号到期清理发生并发冲突: " + userId);
            }
            invalidateUserCacheAfterCommit(userId);
            completed++;
        }
        return completed;
    }

    private void scrubPrivateData(long userId) {
        // API Key 密文没有审计价值，到期后直接物理清除。
        jdbc.update("DELETE FROM user_ai_provider_setting WHERE user_id=?", userId);

        // 私信与站内通知保留外键骨架，但正文必须不可逆脱敏，避免另一参与者的
        // 历史列表因为物理删除而损坏，也避免注销账号的私人文本继续可读。
        jdbc.update("UPDATE chat_msg SET content='[账号已注销]' WHERE from_id=? OR to_id=?", userId, userId);
        jdbc.update("UPDATE message SET content='[账号已注销]' WHERE from_id=? OR to_id=?", userId, userId);

        // 未发布草稿是私人内容，不属于社区公开事实。先删 revision-owned 草稿，
        // 再清理兼容 article 行中的可变正文；已发布文章及其审核修订保持原样。
        jdbc.update("DELETE FROM article_draft WHERE user_id=?", userId);
        jdbc.update("""
                UPDATE article
                SET title='[账号已注销的草稿]',summary=NULL,content=NULL,cover=NULL,
                    update_time=CURRENT_TIMESTAMP
                WHERE author_id=? AND published_revision_id IS NULL AND status<>1
                """, userId);

        // 收藏夹和关注备注属于个人组织数据；公开文章、评论、点赞以及举报审计
        // 继续保留，避免破坏社区计数和内容归属。
        jdbc.update("DELETE FROM favorite WHERE user_id=?", userId);
        jdbc.update("DELETE FROM favorite_folder WHERE user_id=?", userId);
        jdbc.update("DELETE FROM follow WHERE follower_id=? OR followed_id=?", userId, userId);

        // 个性化推荐的行为、曝光和重建检查点只服务当前账号，到期后不再保留。
        jdbc.update("DELETE FROM recommendation_event_outbox WHERE user_id=?", userId);
        jdbc.update("DELETE FROM recommendation_profile_checkpoint WHERE user_id=?", userId);
        jdbc.update("DELETE FROM recommendation_exposure WHERE user_id=?", userId);
        jdbc.update("DELETE FROM user_article_event WHERE user_id=?", userId);
        recommendationProfiles.deleteProfile(userId);

        // 保留外键骨架和运行审计，但清除可识别用户的自由文本与运行上下文。
        jdbc.update("UPDATE agent_profile SET personality_text='',updated_at=CURRENT_TIMESTAMP(6),lock_version=lock_version+1 WHERE user_id=?", userId);
        jdbc.update("UPDATE agent_run_guard SET active_run_id=NULL,active_run_type=NULL,lease_until=NULL,lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE user_id=?", userId);
        jdbc.update("UPDATE agent_turn SET page_context_json=JSON_OBJECT(),error_code='ACCOUNT_DELETED' WHERE user_id=?", userId);
        jdbc.update("UPDATE agent_message SET content='[账号已注销]',content_hash=SHA2(CONCAT('deleted-message:',id),256) WHERE user_id=?", userId);
        jdbc.update("UPDATE agent_tool_call SET arguments_json=JSON_OBJECT(),result_hash=NULL,error_code='ACCOUNT_DELETED' WHERE user_id=?", userId);
        jdbc.update("UPDATE agent_retrieval_hit SET excerpt_snapshot=NULL,metadata_json=JSON_OBJECT() WHERE user_id=?", userId);
        jdbc.update("UPDATE agent_answer_citation SET quote_snapshot='[账号已注销]',quote_hash=SHA2(CONCAT('deleted-citation:',id),256),state='REDACTED',redacted_at=CURRENT_TIMESTAMP(6) WHERE user_id=?", userId);
        jdbc.update("UPDATE agent_episode SET summary_text=NULL,summary_hash=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE user_id=?", userId);

        // 记忆事实保留不可逆的删除审计；向量投影转为 DELETING，交给投影恢复流程收敛外部存储。
        jdbc.update("UPDATE agent_memory_setting SET enabled=0,updated_at=CURRENT_TIMESTAMP(6),lock_version=lock_version+1 WHERE user_id=?", userId);
        jdbc.update("UPDATE agent_memory_item SET current_version_id=NULL,state='DELETED',deleted_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6),lock_version=lock_version+1 WHERE user_id=?", userId);
        jdbc.update("UPDATE agent_memory_version SET content='[账号已注销]',normalized_content='[deleted]',content_hash=SHA2(CONCAT('deleted-memory:',id),256),state='DELETED' WHERE user_id=?", userId);
        jdbc.update("UPDATE agent_memory_projection SET state='DELETING',last_error_code=NULL,lock_version=lock_version+1 WHERE user_id=? AND state<>'DELETED'", userId);
    }

    /**
     * 缓存失效必须发生在数据库提交之后。若在事务内先删缓存，并发请求可能在
     * 提交前读到旧的 ACTIVE 行并重新写回长 TTL 缓存，导致旧令牌继续可用。
     */
    private void invalidateUserCacheAfterCommit(long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            users.clearUserCache(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                users.clearUserCache(userId);
            }
        });
    }

    private static AccountDeletionRecord requireRecord(AccountDeletionRecord record) {
        if (record == null) {
            throw new IllegalStateException("账号不存在或已经完成注销");
        }
        return record;
    }
}
