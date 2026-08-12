package cumt.zongzuo.community.ai.userprovider;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 用户 AI 凭据的所有权绑定 SQL，所有读写都必须携带 user_id。 */
@Mapper
public interface UserAiProviderMapper {

    @Select("""
            SELECT user_id,provider,base_url,model,encrypted_api_key,key_hint,enabled
            FROM user_ai_provider_setting WHERE user_id=#{userId}
            """)
    UserAiProviderRecord findByUserId(long userId);

    /**
     * 一位用户只保留一份当前配置。替换 Key 时覆盖密文，不保存历史明文或旧密文，
     * 从而让“删除/替换凭据”的含义清晰可验证。
     */
    @Insert("""
            INSERT INTO user_ai_provider_setting(user_id,provider,base_url,model,
              encrypted_api_key,key_hint,enabled,created_at,updated_at,lock_version)
            VALUES (#{userId},#{provider},#{baseUrl},#{model},#{encryptedApiKey},#{keyHint},
              #{enabled},CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0)
            ON DUPLICATE KEY UPDATE provider=VALUES(provider),base_url=VALUES(base_url),
              model=VALUES(model),encrypted_api_key=VALUES(encrypted_api_key),
              key_hint=VALUES(key_hint),enabled=VALUES(enabled),updated_at=CURRENT_TIMESTAMP(6),
              lock_version=lock_version+1
            """)
    int upsert(UserAiProviderRecord record);

    @Update("""
            UPDATE user_ai_provider_setting SET enabled=#{enabled},
              updated_at=CURRENT_TIMESTAMP(6),lock_version=lock_version+1
            WHERE user_id=#{userId}
            """)
    int setEnabled(long userId, boolean enabled);

    @Delete("DELETE FROM user_ai_provider_setting WHERE user_id=#{userId}")
    int deleteByUserId(long userId);
}
