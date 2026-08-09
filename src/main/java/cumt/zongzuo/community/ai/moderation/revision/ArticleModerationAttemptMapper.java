package cumt.zongzuo.community.ai.moderation.revision;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ArticleModerationAttemptMapper {

    @Insert("""
            INSERT INTO article_moderation_attempt
                (job_id, attempt_no, provider, model, prompt_version, input_hash,
                 structured_output_json, latency_ms, token_usage_json, finish_reason,
                 error_code, created_at)
            VALUES
                (#{row.jobId}, #{row.attemptNo}, #{row.provider}, #{row.model},
                 #{row.promptVersion}, #{row.inputHash}, #{row.structuredOutputJson},
                 #{row.latencyMs}, #{row.tokenUsageJson}, #{row.finishReason},
                 #{row.errorCode}, #{row.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insert(@Param("row") ArticleModerationAttempt row);

    @Select("SELECT * FROM article_moderation_attempt WHERE id = #{id}")
    ArticleModerationAttempt selectById(@Param("id") long id);
}
