package cumt.zongzuo.community.account;

import cn.hutool.crypto.digest.BCrypt;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号注销的数据库与鉴权契约。
 *
 * <p>这里故意使用真实 HTTP、真实 JWT 与真实 MySQL，避免只验证 Service mock，
 * 却遗漏旧令牌仍能访问私人接口的安全漏洞。</p>
 */
class AccountDeletionIntegrationTest extends IntegrationTestSupport {

    private static final long USER_ID = 88_101L;
    private static final long COLLIDING_USERNAME_USER_ID = 88_102L;
    private static final long PRIVATE_ARTICLE_ID = 88_901L;
    private static final long FAVORITE_FOLDER_ID = 88_911L;

    @Autowired
    private JwtService tokens;

    @Autowired
    private AccountDeletionService deletions;

    @Autowired
    private StringRedisTemplate redis;

    private HttpHeaders headers;

    @BeforeEach
    void seedUser() {
        jdbcTemplate.update("DELETE FROM recommendation_event_outbox WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM recommendation_profile_checkpoint WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM recommendation_exposure WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM user_article_event WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM favorite WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM favorite_folder WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM follow WHERE follower_id=? OR followed_id=?", USER_ID, USER_ID);
        jdbcTemplate.update("DELETE FROM chat_msg WHERE from_id=? OR to_id=?", USER_ID, USER_ID);
        jdbcTemplate.update("DELETE FROM message WHERE from_id=? OR to_id=?", USER_ID, USER_ID);
        jdbcTemplate.update("DELETE FROM article_draft WHERE article_id=?", PRIVATE_ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article WHERE id=?", PRIVATE_ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM user_ai_provider_setting WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_memory_setting WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_profile WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM agent_run_guard WHERE user_id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM sys_user WHERE id=?", USER_ID);
        jdbcTemplate.update("DELETE FROM sys_user WHERE id=?", COLLIDING_USERNAME_USER_ID);
        jdbcTemplate.update("""
                INSERT INTO sys_user
                    (id,username,password,email,role,status,deleted,account_state,
                     deletion_requested_at,purge_after,deletion_version)
                VALUES (?, 'grace-user', ?, 'grace-user@example.com', 0, 0, 0,
                        'ACTIVE', NULL, NULL, 0)
                """, USER_ID, BCrypt.hashpw("secret123"));
        headers = new HttpHeaders();
        headers.setBearerAuth(tokens.generate(USER_ID));
        headers.setContentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void pendingAccountCanLoginOnlyToObtainARestrictedRecoveryToken() {
        restTemplate.postForEntity(url("/api/user/account-deletion/request"),
                new HttpEntity<>("{\"confirmation\":\"DELETE_MY_ACCOUNT\"}", headers), String.class);

        ResponseEntity<String> login = restTemplate.postForEntity(url("/api/auth/login"),
                new HttpEntity<>("{\"email\":\"grace-user@example.com\",\"password\":\"secret123\"}",
                        headers), String.class);

        assertThat(login.getStatusCode().value()).isEqualTo(200);
        assertThat(login.getBody()).contains("PENDING_DELETE", "purgeAfter", "token");
    }

    @Test
    void requestBlocksNormalApisButTheSameTokenCanRestoreDuringSevenDayGrace() {
        ResponseEntity<String> requested = restTemplate.postForEntity(
                url("/api/user/account-deletion/request"),
                new HttpEntity<>("{\"confirmation\":\"DELETE_MY_ACCOUNT\"}", headers),
                String.class);

        assertThat(requested.getStatusCode().value()).isEqualTo(200);
        assertThat(requested.getBody()).contains("PENDING_DELETE", "purgeAfter");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(DAY,deletion_requested_at,purge_after) FROM sys_user WHERE id=?",
                Integer.class, USER_ID)).isEqualTo(7);

        ResponseEntity<String> blocked = restTemplate.exchange(
                url("/api/user/info"), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(blocked.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> restored = restTemplate.postForEntity(
                url("/api/user/account-deletion/restore"), new HttpEntity<>(headers), String.class);
        assertThat(restored.getStatusCode().value()).isEqualTo(200);
        assertThat(restored.getBody()).contains("ACTIVE");
        assertThat(restTemplate.exchange(url("/api/user/info"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void dueAccountIsAnonymizedAndLogicallyDeletedWithoutRemovingItsAuditRow() {
        // 活跃用户可能合法占用可预测的 deleted-user-{id} 名称。注销名称必须使用
        // 不可预测的脱敏值，否则他人能够利用唯一索引阻塞到期清理。
        jdbcTemplate.update("""
                INSERT INTO sys_user
                    (id,username,password,email,role,status,deleted,account_state,deletion_version)
                VALUES (?, ?, ?, 'collision@example.com', 0, 0, 0, 'ACTIVE', 0)
                """, COLLIDING_USERNAME_USER_ID, "deleted-user-" + USER_ID,
                BCrypt.hashpw("secret123"));
        jdbcTemplate.update("""
                INSERT INTO user_ai_provider_setting
                    (user_id,provider,base_url,model,encrypted_api_key,key_hint,enabled,
                     created_at,updated_at,lock_version)
                VALUES (?, 'QWEN', 'https://example.com/v1', 'qwen', 'ciphertext', '••••key', 1,
                        CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0)
                """, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO agent_profile(user_id,personality_text,created_at,updated_at,lock_version)
                VALUES (?, 'private personality',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0)
                """, USER_ID);
        redis.opsForZSet().add("recommendation:tag:" + USER_ID, "private-tag", 1D);
        redis.opsForZSet().add("recommendation:author:" + USER_ID, "999", 1D);
        redis.opsForValue().set("recommendation:feed:request:" + USER_ID, "9");
        seedPrivateAccountData();

        restTemplate.postForEntity(url("/api/user/account-deletion/request"),
                new HttpEntity<>("{\"confirmation\":\"DELETE_MY_ACCOUNT\"}", headers), String.class);
        jdbcTemplate.update("UPDATE sys_user SET purge_after=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",
                USER_ID);

        assertThat(deletions.purgeDue(20)).isEqualTo(1);

        var row = jdbcTemplate.queryForMap("""
                SELECT username,email,avatar,intro,deleted,account_state,purge_after
                FROM sys_user WHERE id=?
                """, USER_ID);
        assertThat(row.get("account_state")).isEqualTo("DELETED");
        assertThat(row.get("deleted")).isEqualTo(true);
        assertThat(row.get("username").toString())
                .startsWith("deleted-")
                .isNotEqualTo("deleted-user-" + USER_ID)
                .hasSize(50);
        assertThat(row.get("email")).isNull();
        assertThat(row.get("avatar")).isNull();
        assertThat(row.get("intro")).isNull();
        assertThat(row.get("purge_after")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_ai_provider_setting WHERE user_id=?", Integer.class, USER_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT personality_text FROM agent_profile WHERE user_id=?", String.class, USER_ID)).isEqualTo("");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM chat_msg WHERE from_id=?", String.class, USER_ID)).isEqualTo("[账号已注销]");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM message WHERE to_id=?", String.class, USER_ID)).isEqualTo("[账号已注销]");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_draft WHERE user_id=?", Integer.class, USER_ID)).isZero();
        var privateArticle = jdbcTemplate.queryForMap(
                "SELECT title,summary,content,cover FROM article WHERE id=?", PRIVATE_ARTICLE_ID);
        assertThat(privateArticle.get("title")).isEqualTo("[账号已注销的草稿]");
        assertThat(privateArticle.get("summary")).isNull();
        assertThat(privateArticle.get("content")).isNull();
        assertThat(privateArticle.get("cover")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_folder WHERE user_id=?", Integer.class, USER_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite WHERE user_id=?", Integer.class, USER_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM follow WHERE follower_id=? OR followed_id=?",
                Integer.class, USER_ID, USER_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recommendation_event_outbox WHERE user_id=?", Integer.class, USER_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recommendation_profile_checkpoint WHERE user_id=?", Integer.class, USER_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recommendation_exposure WHERE user_id=?", Integer.class, USER_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_article_event WHERE user_id=?", Integer.class, USER_ID)).isZero();
        assertThat(redis.hasKey("recommendation:tag:" + USER_ID)).isFalse();
        assertThat(redis.hasKey("recommendation:author:" + USER_ID)).isFalse();
        assertThat(redis.hasKey("recommendation:feed:request:" + USER_ID)).isFalse();

        // 账号行仍存在用于文章作者、审核与安全审计；这里只做逻辑删除和隐私脱敏。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE id=?", Integer.class, USER_ID)).isOne();
        assertThat(restTemplate.exchange(url("/api/user/account-deletion"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode().value()).isEqualTo(401);
    }

    /**
     * 构造会泄漏真实隐私的代表性数据，确保到期清理不是只改 sys_user 状态。
     * 已发布文章和公开审计不在这里构造，因为它们按产品契约必须继续保留。
     */
    private void seedPrivateAccountData() {
        jdbcTemplate.update("""
                INSERT INTO article
                    (id,title,summary,content,author_id,status,cover,is_deleted,
                     lifecycle_epoch,lock_version)
                VALUES (?, '私人草稿', '私人摘要', '私人正文', ?, 0, 'private-cover', 0, 1, 0)
                """, PRIVATE_ARTICLE_ID, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO article_draft
                    (article_id,user_id,draft_version,title,summary,body_markdown,body_plain,
                     cover,tags_json,content_hash,created_at,updated_at,lock_version)
                VALUES (?, ?, 1, '私人草稿', '私人摘要', '私人正文', '私人正文',
                        'private-cover', JSON_ARRAY('private'), REPEAT('a',64),
                        CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0)
                """, PRIVATE_ARTICLE_ID, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO chat_msg(from_id,to_id,content,create_time,status)
                VALUES (?, ?, '私人聊天内容', CURRENT_TIMESTAMP, 0)
                """, USER_ID, COLLIDING_USERNAME_USER_ID);
        jdbcTemplate.update("""
                INSERT INTO message(from_id,to_id,type,content,status,create_time)
                VALUES (?, ?, 4, '私人通知内容', 0, CURRENT_TIMESTAMP)
                """, COLLIDING_USERNAME_USER_ID, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO favorite_folder(id,user_id,name,description,is_public,create_time)
                VALUES (?, ?, '私人收藏夹', '私人收藏描述', 0, CURRENT_TIMESTAMP)
                """, FAVORITE_FOLDER_ID, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO favorite(user_id,article_id,folder_id,create_time)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, USER_ID, PRIVATE_ARTICLE_ID, FAVORITE_FOLDER_ID);
        jdbcTemplate.update("""
                INSERT INTO follow(follower_id,followed_id,create_time,remark,description)
                VALUES (?, ?, CURRENT_TIMESTAMP, '私人备注', '私人描述')
                """, USER_ID, COLLIDING_USERNAME_USER_ID);
        jdbcTemplate.update("""
                INSERT INTO user_article_event
                    (user_id,article_id,target_author_id,event_type,occurred_at,dedupe_key,source)
                VALUES (?, ?, ?, 'VIEW', CURRENT_TIMESTAMP, 'delete-user-event', 'TEST')
                """, USER_ID, PRIVATE_ARTICLE_ID, COLLIDING_USERNAME_USER_ID);
        jdbcTemplate.update("""
                INSERT INTO recommendation_profile_checkpoint
                    (user_id,requested_event_id,rebuilt_event_id,needs_rebuild)
                VALUES (?, 1, 0, 1)
                """, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO recommendation_event_outbox
                    (user_id,article_id,target_author_id,event_type,occurred_at,dedupe_key,source)
                VALUES (?, ?, ?, 'VIEW', CURRENT_TIMESTAMP, 'delete-user-outbox', 'TEST')
                """, USER_ID, PRIVATE_ARTICLE_ID, COLLIDING_USERNAME_USER_ID);
        jdbcTemplate.update("""
                INSERT INTO recommendation_exposure
                    (user_id,article_id,article_author_id,session_id,source,
                     tag_affinity,author_affinity,similar_score,heat_score,freshness_score,exposed_at)
                VALUES (?, ?, ?, 'delete-session', 'TEST', 1, 1, 1, 1, 1, CURRENT_TIMESTAMP)
                """, USER_ID, PRIVATE_ARTICLE_ID, COLLIDING_USERNAME_USER_ID);
    }
}
