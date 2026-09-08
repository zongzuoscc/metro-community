package cumt.zongzuo.community.ai.agent.history;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 搜索已终结的持久消息，并在 SQL 中强制 user_id 所有权边界。
 * 候选查询有固定上限，避免一次回忆问题扫描用户的全部消息内容。
 */
@Mapper
public interface AgentConversationHistoryMapper {

    /** 旧调用只读取第一页；对话组装使用下面带游标的接口继续向前翻页。 */
    default List<AgentConversationHistoryHit> recentCompletedMessages(long userId, int turnLimit) {
        return completedMessagesBefore(userId, Long.MAX_VALUE, turnLimit);
    }

    /** 先限制成功 turn 数再取成对消息，游标基于 turn ID，不能用消息 ID 或 OFFSET。 */
    @Select("""
            SELECT m.id AS message_id,m.turn_id,m.user_id,m.role,m.content,m.created_at
            FROM agent_message m
            JOIN (
                SELECT t.id,t.user_id FROM agent_turn t
                JOIN agent_episode e ON e.id=t.episode_id AND e.user_id=t.user_id
                JOIN agent_conversation c ON c.id=t.conversation_id AND c.user_id=t.user_id
                WHERE t.user_id=#{userId} AND t.state='SUCCEEDED'
                  AND t.id < #{beforeTurnId}
                  AND e.episode_no >= c.context_start_episode_no
                  AND EXISTS (SELECT 1 FROM agent_message u WHERE u.turn_id=t.id
                      AND u.user_id=t.user_id AND u.role='USER' AND u.state='FINAL')
                  AND EXISTS (SELECT 1 FROM agent_message a WHERE a.turn_id=t.id
                      AND a.user_id=t.user_id AND a.role='ASSISTANT' AND a.state='FINAL')
                ORDER BY t.id DESC LIMIT #{turnLimit}
            ) recent ON recent.id=m.turn_id AND recent.user_id=m.user_id
            WHERE m.user_id=#{userId} AND m.state='FINAL' AND m.role IN ('USER','ASSISTANT')
            ORDER BY m.id ASC
            """)
    @org.apache.ibatis.annotations.Options(timeout = 2)
    List<AgentConversationHistoryHit> completedMessagesBefore(@Param("userId") long userId,
                                                              @Param("beforeTurnId") long beforeTurnId,
                                                              @Param("turnLimit") int turnLimit);

    @Select("""
            SELECT e.id AS episode_id,e.episode_no,e.summary_text AS summary,e.sealed_at
            FROM agent_episode e
            JOIN agent_conversation c ON c.id=e.conversation_id AND c.user_id=e.user_id
            WHERE e.user_id=#{userId} AND e.state='READY' AND e.summary_text IS NOT NULL
              AND e.episode_no >= c.context_start_episode_no
            ORDER BY e.episode_no DESC LIMIT #{limit}
            """)
    List<AgentEpisodeSummaryView> recentSummaries(@Param("userId") long userId,
                                                  @Param("limit") int limit);

    @Select("""
            <script>
            SELECT m.id AS message_id,m.turn_id,m.user_id,m.role,m.content,m.created_at
            FROM agent_message m
            JOIN agent_episode e ON e.id=m.episode_id AND e.user_id=m.user_id
            JOIN agent_conversation c ON c.id=m.conversation_id AND c.user_id=m.user_id
            WHERE m.user_id=#{userId} AND m.state='FINAL'
              AND e.episode_no >= c.context_start_episode_no
              AND m.id &lt; #{beforeMessageId}
              AND (
                <foreach collection="terms" item="term" separator=" OR ">
                  m.content LIKE CONCAT('%',#{term},'%')
                </foreach>
              )
            ORDER BY m.id DESC LIMIT #{candidateLimit}
            </script>
            """)
    List<AgentConversationHistoryHit> searchCandidates(@Param("userId") long userId,
                                                       @Param("terms") List<String> terms,
                                                       @Param("beforeMessageId") long beforeMessageId,
                                                       @Param("candidateLimit") int candidateLimit);
}
