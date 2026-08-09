package cumt.zongzuo.community.recommendation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.recommendation.entity.RecommendationExposure;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RecommendationExposureMapper extends BaseMapper<RecommendationExposure> {

    @Insert("""
            INSERT INTO recommendation_exposure
                (user_id, article_id, article_author_id, session_id, source, tag_affinity, author_affinity,
                 similar_score, heat_score, freshness_score, source_follow, source_tag,
                 source_similar, source_explore, baseline_score, exposed_at, create_time)
            VALUES
                (#{exposure.userId}, #{exposure.articleId}, #{exposure.articleAuthorId},
                 #{exposure.sessionId}, #{exposure.source},
                 #{exposure.tagAffinity}, #{exposure.authorAffinity}, #{exposure.similarScore},
                 #{exposure.heatScore}, #{exposure.freshnessScore}, #{exposure.sourceFollow},
                 #{exposure.sourceTag}, #{exposure.sourceSimilar}, #{exposure.sourceExplore},
                 #{exposure.baselineScore}, #{exposure.exposedAt}, #{exposure.createTime})
            ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """)
    int insertIfAbsent(@Param("exposure") RecommendationExposure exposure);

    @Select("""
            SELECT * FROM recommendation_exposure
            WHERE user_id = #{userId} AND article_id = #{articleId} AND session_id = #{sessionId}
            LIMIT 1
            """)
    RecommendationExposure selectByIdentity(@Param("userId") Long userId,
                                            @Param("articleId") Long articleId,
                                            @Param("sessionId") String sessionId);
}
