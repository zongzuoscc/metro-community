package cumt.zongzuo.community.ai.agent.history;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 长对话滚动摘要的领取、完成和只读召回 SQL 边界。 */
@Mapper
public interface AgentEpisodeSummaryMapper {

    @Select("""
            SELECT id AS episode_id,user_id,state FROM agent_episode
            WHERE state='SEALED'
               OR (state='SUMMARIZING' AND updated_at<DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 5 MINUTE))
            ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    AgentEpisodeSummaryClaim selectNextForUpdate();

    @Update("""
            UPDATE agent_episode SET state='SUMMARIZING',updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=#{episodeId} AND user_id=#{userId} AND state=#{expectedState}
            """)
    int claim(@Param("episodeId") long episodeId, @Param("userId") long userId,
              @Param("expectedState") String expectedState);

    @Select("""
            SELECT role,content FROM agent_message
            WHERE episode_id=#{episodeId} AND user_id=#{userId} AND state='FINAL'
            ORDER BY id LIMIT 80
            """)
    List<AgentEpisodeMessage> messages(@Param("episodeId") long episodeId,
                                       @Param("userId") long userId);

    @Update("""
            UPDATE agent_episode SET state='READY',summary_text=#{summary},summary_hash=#{summaryHash},
              updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=#{episodeId} AND user_id=#{userId} AND state='SUMMARIZING'
            """)
    int complete(@Param("episodeId") long episodeId, @Param("userId") long userId,
                 @Param("summary") String summary, @Param("summaryHash") String summaryHash);

    @Update("""
            UPDATE agent_episode SET state='FAILED',summary_text=NULL,summary_hash=NULL,
              updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=#{episodeId} AND user_id=#{userId} AND state='SUMMARIZING'
            """)
    int fail(@Param("episodeId") long episodeId, @Param("userId") long userId);

    @Select("""
            SELECT id AS episode_id,episode_no,summary_text AS summary,sealed_at
            FROM agent_episode
            WHERE user_id=#{userId} AND state='READY' AND summary_text IS NOT NULL
            ORDER BY episode_no DESC LIMIT #{limit}
            """)
    List<AgentEpisodeSummaryView> recent(@Param("userId") long userId,
                                         @Param("limit") int limit);
}
