package cumt.zongzuo.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    @Insert("""
            INSERT INTO message
              (from_id,to_id,type,target_id,content,status,create_time,source_event_id)
            VALUES
              (#{row.fromId},#{row.toId},#{row.type},#{row.targetId},#{row.content},
               #{row.status},#{row.createTime},
               #{row.sourceEventId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler})
            ON DUPLICATE KEY UPDATE source_event_id=source_event_id
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertEventMessage(@Param("row") Message row);

    // 统计某用户的未读消息总数
    @Select("SELECT COUNT(*) FROM message WHERE to_id = #{userId} AND status = 0")
    Long selectUnreadCount(Long userId);

    // 一键已读 (把该用户的所有消息状态改为1)
    @Update("UPDATE message SET status = 1 WHERE to_id = #{userId} AND status = 0")
    void readAll(Long userId);
}
