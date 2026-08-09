package cumt.zongzuo.community.event.projection;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

@Mapper
public interface ConsumerInboxMapper {

    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM consumer_inbox
              WHERE consumer_name = #{consumer}
                AND event_id = #{eventId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler}
            )
            """)
    boolean exists(@Param("consumer") String consumer, @Param("eventId") UUID eventId);

    @Insert("""
            INSERT INTO consumer_inbox (consumer_name, event_id, processed_at, result_hash)
            VALUES (#{consumer},
              #{eventId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler},
              CURRENT_TIMESTAMP(6), #{resultHash})
            """)
    int insert(@Param("consumer") String consumer,
               @Param("eventId") UUID eventId,
               @Param("resultHash") String resultHash);
}
