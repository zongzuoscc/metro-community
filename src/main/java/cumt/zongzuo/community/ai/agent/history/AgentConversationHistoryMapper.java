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
            <script>
            SELECT id AS message_id,turn_id,user_id,role,content,created_at
            FROM agent_message
            WHERE user_id=#{userId} AND state='FINAL'
              AND id &lt; #{beforeMessageId}
              AND (
                <foreach collection="terms" item="term" separator=" OR ">
                  content LIKE CONCAT('%',#{term},'%')
                </foreach>
              )
            ORDER BY id DESC LIMIT #{candidateLimit}
            </script>
            """)
    List<AgentConversationHistoryHit> searchCandidates(@Param("userId") long userId,
                                                       @Param("terms") List<String> terms,
                                                       @Param("beforeMessageId") long beforeMessageId,
                                                       @Param("candidateLimit") int candidateLimit);
}
