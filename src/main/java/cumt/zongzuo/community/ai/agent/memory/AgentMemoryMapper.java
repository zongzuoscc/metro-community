package cumt.zongzuo.community.ai.agent.memory;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 长期记忆事项、不可变版本、来源证据与派生投影的 SQL 合约。
 * 需要所有者隔离的操作都在 SQL WHERE/JOIN 中绑定 user_id，不依赖 Controller 层的二次过滤。
 */
@Mapper
public interface AgentMemoryMapper {

    @Insert("""
            INSERT INTO agent_memory_setting(user_id,enabled,sensitive_projection_enabled,
              created_at,updated_at,lock_version)
            VALUES (#{userId},1,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0)
            ON DUPLICATE KEY UPDATE user_id=user_id
            """)
    int ensureSetting(long userId);

    @Select("SELECT enabled FROM agent_memory_setting WHERE user_id=#{userId}")
    Boolean enabled(long userId);

    @Select("SELECT lock_version FROM agent_memory_setting WHERE user_id=#{userId}")
    Long settingVersion(long userId);

    @Select("""
            SELECT m.id,m.category,v.content,v.version_no AS version,m.state,m.expires_at,
              CASE WHEN EXISTS(SELECT 1 FROM agent_memory_source s
                WHERE s.memory_id=m.id AND s.user_id=m.user_id) THEN 'CONVERSATION' ELSE 'MANUAL' END AS source_type
            FROM agent_memory_item m JOIN agent_memory_version v
              ON v.id=m.current_version_id AND v.user_id=m.user_id
            WHERE m.user_id=#{userId} AND m.state IN ('ACTIVE','PAUSED')
            ORDER BY m.updated_at DESC,m.id DESC LIMIT #{limit}
            """)
    List<AgentMemoryView> listManaged(@Param("userId") long userId, @Param("limit") int limit);

    @Select("""
            SELECT m.id,m.category,v.content,v.version_no AS version,m.state,m.expires_at,
              CASE WHEN EXISTS(SELECT 1 FROM agent_memory_source s
                WHERE s.memory_id=m.id AND s.user_id=m.user_id) THEN 'CONVERSATION' ELSE 'MANUAL' END AS source_type
            FROM agent_memory_item m JOIN agent_memory_version v
              ON v.id=m.current_version_id AND v.user_id=m.user_id
            WHERE m.user_id=#{userId} AND m.state='ACTIVE'
              AND (m.expires_at IS NULL OR m.expires_at>CURRENT_TIMESTAMP(6))
            ORDER BY m.updated_at DESC,m.id DESC LIMIT #{limit}
            """)
    List<AgentMemoryView> listActive(@Param("userId") long userId, @Param("limit") int limit);

    @Select("""
            SELECT m.id,m.category,v.content,v.version_no AS version,m.state,m.expires_at,
              CASE WHEN EXISTS(SELECT 1 FROM agent_memory_source s
                WHERE s.memory_id=m.id AND s.user_id=m.user_id) THEN 'CONVERSATION' ELSE 'MANUAL' END AS source_type
            FROM agent_memory_item m JOIN agent_memory_version v
              ON v.id=m.current_version_id AND v.user_id=m.user_id
            WHERE m.id=#{memoryId} AND m.user_id=#{userId} AND m.state<>'DELETED'
            """)
    AgentMemoryView find(@Param("memoryId") long memoryId, @Param("userId") long userId);

    @Select("""
            SELECT id FROM agent_message
            WHERE turn_id=#{turnId} AND user_id=#{userId} AND role='USER' AND state='FINAL'
            """)
    Long sourceMessageId(@Param("turnId") long turnId, @Param("userId") long userId);

    @Select("SELECT content FROM agent_message WHERE id=#{messageId} AND user_id=#{userId}")
    String messageContent(@Param("messageId") long messageId, @Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM agent_memory_source WHERE source_message_id=#{messageId} AND user_id=#{userId}")
    int sourceCount(@Param("messageId") long messageId, @Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM agent_memory_version WHERE user_id=#{userId} AND content_hash=#{contentHash}")
    int contentHashCount(@Param("userId") long userId, @Param("contentHash") String contentHash);

    @Insert("""
            INSERT INTO agent_memory_item(user_id,current_version_id,category,sensitivity,state,
              expires_at,created_at,updated_at,deleted_at,lock_version)
            VALUES (#{userId},NULL,#{category},'LOW','ACTIVE',#{expiresAt},
              CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),NULL,0)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertItem(MemoryInsert insert);

    @Insert("""
            INSERT INTO agent_memory_version(user_id,memory_id,version_no,content,
              normalized_content,content_hash,state,created_at)
            VALUES (#{userId},#{memoryId},#{versionNo},#{content},#{normalizedContent},
              #{contentHash},'ACTIVE',CURRENT_TIMESTAMP(6))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertVersion(MemoryVersionInsert insert);

    @Update("""
            UPDATE agent_memory_item SET current_version_id=#{versionId},updated_at=CURRENT_TIMESTAMP(6),
              lock_version=lock_version+1 WHERE id=#{memoryId} AND user_id=#{userId}
            """)
    int activateVersion(@Param("memoryId") long memoryId, @Param("userId") long userId,
                        @Param("versionId") long versionId);

    @Insert("""
            INSERT INTO agent_memory_source(user_id,memory_id,memory_version_id,source_turn_id,
              source_message_id,created_at)
            VALUES (#{userId},#{memoryId},#{versionId},#{turnId},#{messageId},CURRENT_TIMESTAMP(6))
            """)
    int insertSource(@Param("userId") long userId, @Param("memoryId") long memoryId,
                     @Param("versionId") long versionId, @Param("turnId") long turnId,
                     @Param("messageId") long messageId);

    @Insert("""
            INSERT INTO agent_memory_projection(memory_version_id,user_id,state,embedding_model,
              projected_at,last_error_code,lock_version)
            VALUES (#{versionId},#{userId},'PENDING',NULL,NULL,NULL,0)
            """)
    int insertProjection(@Param("versionId") long versionId, @Param("userId") long userId);

    @Update("""
            UPDATE agent_memory_version SET state='SUPERSEDED'
            WHERE id=(SELECT current_version_id FROM agent_memory_item WHERE id=#{memoryId} AND user_id=#{userId})
              AND user_id=#{userId} AND state='ACTIVE'
            """)
    int supersedeCurrent(@Param("memoryId") long memoryId, @Param("userId") long userId);

    @Update("""
            UPDATE agent_memory_projection p
            JOIN agent_memory_version v ON v.id=p.memory_version_id AND v.user_id=p.user_id
            SET p.state='DELETING',p.last_error_code=NULL,p.lock_version=p.lock_version+1
            WHERE v.memory_id=#{memoryId} AND v.user_id=#{userId}
              AND v.state='SUPERSEDED' AND p.state IN ('PENDING','PROJECTED','FAILED')
            """)
    int deleteSupersededProjections(@Param("memoryId") long memoryId,
                                    @Param("userId") long userId);

    @Update("""
            UPDATE agent_memory_item SET current_version_id=#{versionId},updated_at=CURRENT_TIMESTAMP(6),
              lock_version=lock_version+1
            WHERE id=#{memoryId} AND user_id=#{userId} AND state='ACTIVE'
              AND lock_version=#{expectedLockVersion}
            """)
    int updateCurrent(@Param("memoryId") long memoryId, @Param("userId") long userId,
                      @Param("versionId") long versionId,
                      @Param("expectedLockVersion") long expectedLockVersion);

    @Select("SELECT lock_version FROM agent_memory_item WHERE id=#{memoryId} AND user_id=#{userId} FOR UPDATE")
    Long itemLockVersion(@Param("memoryId") long memoryId, @Param("userId") long userId);

    @Update("""
            UPDATE agent_memory_item SET state=#{targetState},updated_at=CURRENT_TIMESTAMP(6),
              lock_version=lock_version+1
            WHERE id=#{memoryId} AND user_id=#{userId} AND state=#{expectedState}
              AND lock_version=#{expectedLockVersion}
            """)
    int updateState(@Param("memoryId") long memoryId, @Param("userId") long userId,
                    @Param("expectedState") String expectedState,
                    @Param("targetState") String targetState,
                    @Param("expectedLockVersion") long expectedLockVersion);

    @Update("""
            UPDATE agent_memory_item SET expires_at=#{expiresAt},updated_at=CURRENT_TIMESTAMP(6),
              lock_version=lock_version+1
            WHERE id=#{memoryId} AND user_id=#{userId} AND state<>'DELETED'
              AND lock_version=#{expectedLockVersion}
            """)
    int updateExpiry(@Param("memoryId") long memoryId, @Param("userId") long userId,
                     @Param("expiresAt") java.time.LocalDateTime expiresAt,
                     @Param("expectedLockVersion") long expectedLockVersion);

    @Update("""
            UPDATE agent_memory_item SET state='DELETED',deleted_at=CURRENT_TIMESTAMP(6),
              updated_at=CURRENT_TIMESTAMP(6),lock_version=lock_version+1
            WHERE id=#{memoryId} AND user_id=#{userId} AND state<>'DELETED'
            """)
    int deleteItem(@Param("memoryId") long memoryId, @Param("userId") long userId);

    @Update("""
            UPDATE agent_memory_version SET state='DELETED'
            WHERE memory_id=#{memoryId} AND user_id=#{userId} AND state<>'DELETED'
            """)
    int deleteVersions(@Param("memoryId") long memoryId, @Param("userId") long userId);

    @Update("""
            UPDATE agent_memory_projection p
            JOIN agent_memory_version v ON v.id=p.memory_version_id AND v.user_id=p.user_id
            SET p.state='DELETING',p.last_error_code=NULL,p.lock_version=p.lock_version+1
            WHERE v.memory_id=#{memoryId} AND v.user_id=#{userId}
              AND p.state IN ('PENDING','PROJECTED','FAILED')
            """)
    int deleteAllProjections(@Param("memoryId") long memoryId,
                             @Param("userId") long userId);

    @Update("""
            UPDATE agent_memory_setting SET enabled=#{enabled},updated_at=CURRENT_TIMESTAMP(6),
              lock_version=lock_version+1
            WHERE user_id=#{userId} AND lock_version=#{expectedVersion}
            """)
    int updateSetting(@Param("userId") long userId, @Param("enabled") boolean enabled,
                      @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE agent_conversation SET memory_epoch=memory_epoch+1,
              updated_at=CURRENT_TIMESTAMP(6),lock_version=lock_version+1
            WHERE user_id=#{userId}
            """)
    int incrementEpoch(long userId);

    final class MemoryInsert {
        public Long id; public long userId; public String category;
        public java.time.LocalDateTime expiresAt;
        public Long getId() { return id; } public void setId(Long id) { this.id = id; }
        public long getUserId() { return userId; } public String getCategory() { return category; }
        public java.time.LocalDateTime getExpiresAt() { return expiresAt; }
    }

    final class MemoryVersionInsert {
        public Long id; public long userId; public long memoryId; public long versionNo;
        public String content; public String normalizedContent; public String contentHash;
        public Long getId() { return id; } public void setId(Long id) { this.id = id; }
        public long getUserId() { return userId; } public long getMemoryId() { return memoryId; }
        public long getVersionNo() { return versionNo; } public String getContent() { return content; }
        public String getNormalizedContent() { return normalizedContent; }
        public String getContentHash() { return contentHash; }
    }
}
