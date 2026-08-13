package cumt.zongzuo.community.ai.agent.turn;

import cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/**
 * 持久 turn 与用户级共享运行栅栏的 MyBatis 边界。
 *
 * <p>临时模式不会调用 turn/message 写入 SQL，但会复用 agent_run_guard，以确保同一用户最多只有一个
 * PERSISTENT 或 TEMPORARY 生成任务。</p>
 */
@Mapper
public interface AgentTurnMapper {

    @Insert("""
            INSERT IGNORE INTO agent_run_guard
                (user_id,active_run_id,active_run_type,run_fence,lease_until,lock_version,updated_at)
            VALUES (#{userId},NULL,NULL,0,NULL,0,CURRENT_TIMESTAMP(6))
            """)
    int ensureGuard(@Param("userId") long userId);

    @Select("SELECT * FROM agent_run_guard WHERE user_id=#{userId} FOR UPDATE")
    @Results(id = "guard", value = {
            @Result(property = "activeRunId", column = "active_run_id",
                    typeHandler = UuidBinaryTypeHandler.class)
    })
    AgentRunGuardRecord selectGuardForUpdate(@Param("userId") long userId);

    /**
     * 在行锁下回收已过期的临时 guard。
     * 持久 turn 有独立 recovery 可以重放，临时内容不可恢复，因此租约过期后只能释放运行权。
     */
    @Update("""
            UPDATE agent_run_guard
            SET active_run_id=NULL,active_run_type=NULL,lease_until=NULL,
                lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP(6)
            WHERE user_id=#{userId} AND active_run_type='TEMPORARY'
              AND active_run_id IS NOT NULL AND lease_until<CURRENT_TIMESTAMP(6)
              AND lock_version=#{lockVersion}
            """)
    int releaseExpiredTemporaryGuard(@Param("userId") long userId,
                                     @Param("lockVersion") long lockVersion);

    /**
     * 使用预期 lockVersion 原子占用共享栅栏。
     * runType 只允许数据库 CHECK 约束中的 PERSISTENT/TEMPORARY，避免两套互斥机制彼此绕过。
     */
    @Update("""
            UPDATE agent_run_guard
            SET active_run_id=#{runId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler},
                active_run_type=#{runType},run_fence=#{runFence},
                lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL #{leaseSeconds} SECOND),
                lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP(6)
            WHERE user_id=#{userId} AND lock_version=#{lockVersion}
            """)
    int claimGuard(@Param("userId") long userId, @Param("runId") UUID runId,
                   @Param("runType") String runType,
                   @Param("runFence") long runFence, @Param("leaseSeconds") long leaseSeconds,
                   @Param("lockVersion") long lockVersion);

    @Update("""
            UPDATE agent_run_guard
            SET active_run_id=NULL,active_run_type=NULL,lease_until=NULL,
                lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP(6)
            WHERE user_id=#{userId}
              AND active_run_id=#{runId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler}
              AND run_fence=#{runFence}
            """)
    int releaseGuard(@Param("userId") long userId, @Param("runId") UUID runId,
                     @Param("runFence") long runFence);

    @Insert("""
            INSERT IGNORE INTO agent_profile
                (user_id,personality_text,created_at,updated_at,lock_version)
            VALUES (#{userId},NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0)
            """)
    int ensureProfile(@Param("userId") long userId);

    @Insert("""
            INSERT IGNORE INTO agent_conversation
                (user_id,last_message_id,memory_epoch,created_at,updated_at,lock_version)
            VALUES (#{userId},NULL,1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0)
            """)
    int ensureConversation(@Param("userId") long userId);

    @Select("SELECT id FROM agent_conversation WHERE user_id=#{userId} FOR UPDATE")
    Long selectConversationIdForUpdate(@Param("userId") long userId);

    /** 读取当前主对话的联网偏好；新用户由数据库默认值开启。 */
    @Select("SELECT web_search_enabled FROM agent_conversation WHERE user_id=#{userId}")
    Boolean selectConversationWebSearch(@Param("userId") long userId);

    /** 用户切换联网偏好时推进会话版本，便于后续设置页和历史页做乐观锁扩展。 */
    @Update("""
            UPDATE agent_conversation
            SET web_search_enabled=#{enabled},lock_version=lock_version+1,
                updated_at=CURRENT_TIMESTAMP(6)
            WHERE user_id=#{userId}
            """)
    int updateConversationWebSearch(@Param("userId") long userId,
                                    @Param("enabled") boolean enabled);

    @Insert("""
            INSERT IGNORE INTO agent_episode
                (user_id,conversation_id,episode_no,state,opened_at,turn_count,token_count,
                 created_at,updated_at)
            VALUES (#{userId},#{conversationId},1,'ACTIVE',CURRENT_TIMESTAMP(6),0,0,
                    CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """)
    int ensureActiveEpisode(@Param("userId") long userId,
                            @Param("conversationId") long conversationId);

    @Select("""
            SELECT id FROM agent_episode
            WHERE user_id=#{userId} AND conversation_id=#{conversationId} AND state='ACTIVE'
            FOR UPDATE
            """)
    Long selectActiveEpisodeIdForUpdate(@Param("userId") long userId,
                                        @Param("conversationId") long conversationId);

    @Select("""
            SELECT * FROM agent_turn
            WHERE user_id=#{userId} AND conversation_id=#{conversationId}
              AND client_request_id=#{clientRequestId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler}
            """)
    @Results(id = "turn", value = {
            @Result(property = "runId", column = "run_id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(property = "clientRequestId", column = "client_request_id",
                    typeHandler = UuidBinaryTypeHandler.class)
    })
    AgentTurnRecord selectByClientRequest(@Param("userId") long userId,
                                          @Param("conversationId") long conversationId,
                                          @Param("clientRequestId") UUID clientRequestId);

    @Select("SELECT * FROM agent_turn WHERE id=#{turnId} AND user_id=#{userId} FOR UPDATE")
    @ResultMap("turn")
    AgentTurnRecord selectByIdForUpdate(@Param("turnId") long turnId,
                                        @Param("userId") long userId);

    @Select("SELECT * FROM agent_turn WHERE id=#{turnId} AND user_id=#{userId}")
    @ResultMap("turn")
    AgentTurnRecord selectById(@Param("turnId") long turnId, @Param("userId") long userId);

    /**
     * 只枚举当前用户已成功提交的持久 turn。
     * 两个 role 都通过 FINAL message 内连接，因此半成品、失败任务和 Redis 临时对话
     * 不可能混入历史轨道。多取一行由 service 判断是否还有下一页。
     */
    @Select("""
            <script>
            SELECT t.id AS turn_id,
                   LEFT(u.content,120) AS question_preview,
                   LEFT(a.content,240) AS answer_preview,
                   u.content AS user_message,
                   a.content AS final_message,
                   t.created_at
            FROM agent_turn t
            JOIN agent_message u ON u.turn_id=t.id AND u.user_id=t.user_id
              AND u.role='USER' AND u.state='FINAL'
            JOIN agent_message a ON a.turn_id=t.id AND a.user_id=t.user_id
              AND a.role='ASSISTANT' AND a.state='FINAL'
            WHERE t.user_id=#{userId} AND t.state='SUCCEEDED'
            <if test="beforeTurnId != null">AND t.id &lt; #{beforeTurnId}</if>
            ORDER BY t.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<AgentTurnHistoryRow> selectHistory(@Param("userId") long userId,
                                            @Param("beforeTurnId") Long beforeTurnId,
                                            @Param("limit") int limit);

    /**
     * 一次读取当前历史页内的站内引用，避免展开多轮记录时形成逐轮查询。
     * user_id 与 turn_id 同时参与过滤，传入其它账号的 turnId 也不会泄漏来源。
     */
    @Select("""
            <script>
            SELECT m.turn_id,c.ordinal,c.article_id,c.revision_id,c.chunk_id,
                   c.title_snapshot,c.quote_snapshot
            FROM agent_answer_citation c
            JOIN agent_message m ON m.id=c.assistant_message_id AND m.user_id=c.user_id
            WHERE c.user_id=#{userId} AND c.state='ACTIVE'
              AND m.turn_id IN
              <foreach collection="turnIds" item="turnId" open="(" separator="," close=")">
                #{turnId}
              </foreach>
            ORDER BY m.turn_id DESC,c.ordinal
            </script>
            """)
    List<AgentTurnHistoryCitationRow> selectHistoryCitations(
            @Param("userId") long userId, @Param("turnIds") List<Long> turnIds);

    /**
     * 联网来源从回答完成时保存的快照恢复，不重新联网，也不相信回答正文中的 URL。
     */
    @Select("""
            <script>
            SELECT turn_id,rank_no,excerpt_snapshot,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.index')) AS UNSIGNED) AS source_index,
                   JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.url')) AS source_url,
                   JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.siteName')) AS site_name,
                   expires_at>CURRENT_TIMESTAMP(6) AS source_active
            FROM agent_retrieval_hit
            WHERE user_id=#{userId} AND source_type='WEB'
              AND turn_id IN
              <foreach collection="turnIds" item="turnId" open="(" separator="," close=")">
                #{turnId}
              </foreach>
            ORDER BY turn_id DESC,rank_no
            </script>
            """)
    List<AgentTurnHistoryWebSourceRow> selectHistoryWebSources(
            @Param("userId") long userId, @Param("turnIds") List<Long> turnIds);

    @Select("SELECT user_id FROM agent_turn WHERE id=#{turnId}")
    Long selectOwner(@Param("turnId") long turnId);

    @Select("""
            SELECT content FROM agent_message
            WHERE turn_id=#{turnId} AND user_id=#{userId} AND role=#{role}
            """)
    String selectMessageContent(@Param("turnId") long turnId, @Param("userId") long userId,
                                @Param("role") String role);

    @Select("""
            SELECT id FROM agent_message
            WHERE turn_id=#{turnId} AND user_id=#{userId} AND role='ASSISTANT'
            """)
    Long selectAssistantMessageId(@Param("turnId") long turnId, @Param("userId") long userId);

    @Select("""
            SELECT COUNT(*) FROM agent_answer_citation
            WHERE assistant_message_id=#{messageId} AND user_id=#{userId}
            """)
    int countCitations(@Param("messageId") long messageId, @Param("userId") long userId);

    @Update("""
            UPDATE agent_turn
            SET state='CANCELLED',completed_at=CURRENT_TIMESTAMP(6),lease_until=NULL,
                error_code='CANCELLED_BY_USER'
            WHERE id=#{turnId} AND user_id=#{userId}
              AND run_id=#{runId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler}
              AND run_fence=#{runFence} AND state IN ('RECEIVED','RUNNING')
            """)
    int cancelTurn(@Param("turnId") long turnId, @Param("userId") long userId,
                   @Param("runId") UUID runId, @Param("runFence") long runFence);

    @Insert("""
            INSERT INTO agent_turn
                (user_id,conversation_id,episode_id,run_id,client_request_id,request_hash,
                 task_type,page_context_json,grounding_mode,web_search_enabled,state,run_fence,lease_until,
                 created_at,started_at)
            VALUES
                (#{row.userId},#{row.conversationId},#{row.episodeId},
                 #{row.runId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler},
                 #{row.clientRequestId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler},
                 #{row.requestHash},#{row.taskType},#{row.pageContextJson},'MIXED_SOURCES',
                 #{row.webSearchEnabled},'RUNNING',#{row.runFence},
                 DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL #{leaseSeconds} SECOND),
                 CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertTurn(@Param("row") AgentTurnRecord row, @Param("leaseSeconds") long leaseSeconds);

    @Insert("""
            INSERT INTO agent_message
                (user_id,turn_id,conversation_id,episode_id,role,state,content,content_hash,
                 created_at,completed_at)
            VALUES (#{userId},#{turnId},#{conversationId},#{episodeId},'USER','FINAL',
                    #{content},#{contentHash},CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """)
    int insertUserMessage(@Param("userId") long userId, @Param("turnId") long turnId,
                          @Param("conversationId") long conversationId,
                          @Param("episodeId") long episodeId, @Param("content") String content,
                          @Param("contentHash") String contentHash);

    @Insert("""
            INSERT INTO agent_message
                (user_id,turn_id,conversation_id,episode_id,role,state,content,content_hash,
                 created_at,completed_at)
            VALUES (#{userId},#{turnId},#{conversationId},#{episodeId},'ASSISTANT','FINAL',
                    #{content},#{contentHash},CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "message.id")
    int insertAssistantMessage(@Param("message") AgentMessageInsert message,
                               @Param("userId") long userId, @Param("turnId") long turnId,
                               @Param("conversationId") long conversationId,
                               @Param("episodeId") long episodeId, @Param("content") String content,
                               @Param("contentHash") String contentHash);

    @Insert("""
            INSERT INTO agent_answer_citation
                (user_id,assistant_message_id,ordinal,article_id,revision_id,chunk_id,
                 title_snapshot,quote_snapshot,quote_hash,state,created_at)
            VALUES (#{userId},#{messageId},#{ordinal},#{articleId},#{revisionId},#{chunkId},
                    #{title},#{quote},#{quoteHash},'ACTIVE',CURRENT_TIMESTAMP(6))
            """)
    int insertCitation(@Param("userId") long userId, @Param("messageId") long messageId,
                       @Param("ordinal") int ordinal, @Param("articleId") long articleId,
                       @Param("revisionId") long revisionId, @Param("chunkId") long chunkId,
                       @Param("title") String title, @Param("quote") String quote,
                       @Param("quoteHash") String quoteHash);

    @Insert("""
            INSERT INTO agent_retrieval_hit
                (user_id,turn_id,source_type,source_key,article_id,revision_id,chunk_id,
                 memory_id,bm25_score,dense_score,rrf_score,rank_no,excerpt_snapshot,
                 metadata_json,expires_at)
            VALUES (#{userId},#{turnId},#{sourceType},#{sourceKey},NULL,NULL,NULL,
                    #{memoryId},NULL,NULL,1.0,#{rankNo},#{excerpt},#{metadataJson},
                    DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 DAY))
            """)
    int insertPersonalContextUse(@Param("userId") long userId,
                                 @Param("turnId") long turnId,
                                 @Param("sourceType") String sourceType,
                                 @Param("sourceKey") String sourceKey,
                                 @Param("memoryId") Long memoryId,
                                 @Param("rankNo") int rankNo,
                                 @Param("excerpt") String excerpt,
                                 @Param("metadataJson") String metadataJson);

    /** 保存本轮真正采用的联网来源快照，便于历史回看和问题审计。 */
    @Insert("""
            INSERT INTO agent_retrieval_hit
                (user_id,turn_id,source_type,source_key,article_id,revision_id,chunk_id,
                 memory_id,bm25_score,dense_score,rrf_score,rank_no,excerpt_snapshot,
                 metadata_json,expires_at)
            VALUES (#{userId},#{turnId},'WEB',#{sourceKey},NULL,NULL,NULL,NULL,
                    NULL,NULL,1.0,#{rankNo},#{title},#{metadataJson},
                    DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 DAY))
            """)
    int insertWebSourceUse(@Param("userId") long userId,
                           @Param("turnId") long turnId,
                           @Param("sourceKey") String sourceKey,
                           @Param("rankNo") int rankNo,
                           @Param("title") String title,
                           @Param("metadataJson") String metadataJson);

    @Update("""
            UPDATE agent_turn
            SET state='SUCCEEDED',completed_at=CURRENT_TIMESTAMP(6),lease_until=NULL,error_code=NULL
            WHERE id=#{turnId} AND user_id=#{userId}
              AND run_id=#{runId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler}
              AND run_fence=#{runFence} AND state='RUNNING'
            """)
    int completeTurn(@Param("turnId") long turnId, @Param("userId") long userId,
                     @Param("runId") UUID runId, @Param("runFence") long runFence);

    @Update("""
            UPDATE agent_turn
            SET state='FAILED',completed_at=CURRENT_TIMESTAMP(6),lease_until=NULL,
                error_code=#{errorCode}
            WHERE id=#{turnId} AND user_id=#{userId}
              AND run_id=#{runId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler}
              AND run_fence=#{runFence} AND state='RUNNING'
            """)
    int failTurn(@Param("turnId") long turnId, @Param("userId") long userId,
                 @Param("runId") UUID runId, @Param("runFence") long runFence,
                 @Param("errorCode") String errorCode);

    @Select("""
            SELECT * FROM agent_turn
            WHERE state IN ('RECEIVED','RUNNING') AND lease_until<CURRENT_TIMESTAMP(6)
            ORDER BY lease_until,id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    @ResultMap("turn")
    AgentTurnRecord selectExpiredForUpdate();

    @Select("""
            SELECT COUNT(*) FROM agent_turn
            WHERE state IN ('RECEIVED','RUNNING') AND lease_until<CURRENT_TIMESTAMP(6)
            """)
    int countExpired();

    @Update("""
            UPDATE agent_turn
            SET run_id=#{newRunId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler},
                run_fence=#{newFence},state='RUNNING',started_at=CURRENT_TIMESTAMP(6),
                lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL #{leaseSeconds} SECOND),
                error_code=NULL
            WHERE id=#{turnId} AND user_id=#{userId}
              AND run_id=#{oldRunId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler}
              AND run_fence=#{oldFence} AND state IN ('RECEIVED','RUNNING')
              AND lease_until<CURRENT_TIMESTAMP(6)
            """)
    int reclaimTurn(@Param("turnId") long turnId, @Param("userId") long userId,
                    @Param("oldRunId") UUID oldRunId, @Param("oldFence") long oldFence,
                    @Param("newRunId") UUID newRunId, @Param("newFence") long newFence,
                    @Param("leaseSeconds") long leaseSeconds);

    @Update("""
            UPDATE agent_run_guard
            SET active_run_id=#{newRunId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler},
                active_run_type='PERSISTENT',run_fence=#{newFence},
                lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL #{leaseSeconds} SECOND),
                lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP(6)
            WHERE user_id=#{userId}
              AND active_run_id=#{oldRunId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler}
              AND run_fence=#{oldFence} AND lease_until<CURRENT_TIMESTAMP(6)
            """)
    int reclaimGuard(@Param("userId") long userId, @Param("oldRunId") UUID oldRunId,
                     @Param("oldFence") long oldFence, @Param("newRunId") UUID newRunId,
                     @Param("newFence") long newFence, @Param("leaseSeconds") long leaseSeconds);

    @Update("""
            UPDATE agent_run_guard
            SET lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL #{leaseSeconds} SECOND),
                updated_at=CURRENT_TIMESTAMP(6)
            WHERE user_id=#{userId}
              AND active_run_id=#{runId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler}
              AND run_fence=#{runFence}
            """)
    int renewGuardLease(@Param("userId") long userId, @Param("runId") UUID runId,
                        @Param("runFence") long runFence,
                        @Param("leaseSeconds") long leaseSeconds);

    @Update("""
            UPDATE agent_turn
            SET lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL #{leaseSeconds} SECOND)
            WHERE id=#{turnId} AND user_id=#{userId}
              AND run_id=#{runId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler}
              AND run_fence=#{runFence} AND state='RUNNING'
            """)
    int renewTurnLease(@Param("turnId") long turnId, @Param("userId") long userId,
                       @Param("runId") UUID runId, @Param("runFence") long runFence,
                       @Param("leaseSeconds") long leaseSeconds);

    @Update("""
            UPDATE agent_conversation
            SET last_message_id=#{messageId},lock_version=lock_version+1,
                updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=#{conversationId} AND user_id=#{userId}
            """)
    int advanceConversation(@Param("conversationId") long conversationId,
                            @Param("userId") long userId, @Param("messageId") long messageId);

    @Update("""
            UPDATE agent_episode
            SET turn_count=turn_count+1,updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=#{episodeId} AND user_id=#{userId} AND state='ACTIVE'
            """)
    int incrementEpisode(@Param("episodeId") long episodeId, @Param("userId") long userId);

    final class AgentMessageInsert {
        private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }
}
