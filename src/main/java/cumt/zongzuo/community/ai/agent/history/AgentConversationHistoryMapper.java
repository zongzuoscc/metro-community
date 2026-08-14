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

    @Select("""
            SELECT id AS episode_id,episode_no,summary_text AS summary,sealed_at
            FROM agent_episode
            WHERE user_id=#{userId} AND state='READY' AND summary_text IS NOT NULL
            ORDER BY episode_no DESC LIMIT #{limit}
            """)
    List<AgentEpisodeSummaryView> recentSummaries(@Param("userId") long userId,
                                                  @Param("limit") int limit);

    @Select("""
            <script>
            SELECT m.id AS message_id,m.turn_id,m.user_id,m.role,m.content,m.created_at
            FROM agent_message m
            JOIN agent_episode e ON e.id=m.episode_id AND e.user_id=m.user_id AND e.state='ACTIVE'
            WHERE m.user_id=#{userId} AND m.state='FINAL'
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
