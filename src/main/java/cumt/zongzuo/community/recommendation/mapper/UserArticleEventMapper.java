package cumt.zongzuo.community.recommendation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.recommendation.entity.UserArticleEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserArticleEventMapper extends BaseMapper<UserArticleEvent> {

    @Select("SELECT COUNT(*) FROM user_article_event WHERE user_id = #{userId} AND occurred_at >= #{since}")
    long countUserFactsSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM user_article_event WHERE occurred_at >= #{since}")
    long countGlobalFactsSince(@Param("since") LocalDateTime since);

    @Select("SELECT article_id FROM user_article_event " +
            "WHERE user_id = #{userId} AND article_id IS NOT NULL " +
            "AND event_type IN ('VIEW', 'COLLECT') AND occurred_at >= #{since} " +
            "GROUP BY article_id ORDER BY MAX(occurred_at) DESC LIMIT #{limit}")
    List<Long> selectRecentSeedArticleIds(@Param("userId") Long userId,
                                          @Param("since") LocalDateTime since,
                                          @Param("limit") int limit);

    @Select("SELECT DISTINCT article_id FROM user_article_event " +
            "WHERE user_id = #{userId} AND article_id IS NOT NULL AND occurred_at >= #{since}")
    List<Long> selectRecentlyInteractedArticleIds(@Param("userId") Long userId,
                                                   @Param("since") LocalDateTime since);

    @Select("SELECT id FROM user_article_event WHERE dedupe_key = #{dedupeKey} AND user_id = #{userId}")
    Long selectIdByDedupeKey(@Param("dedupeKey") String dedupeKey, @Param("userId") Long userId);
}
