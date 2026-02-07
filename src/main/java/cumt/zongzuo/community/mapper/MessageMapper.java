package cumt.zongzuo.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    // 统计某用户的未读消息总数
    @Select("SELECT COUNT(*) FROM message WHERE to_id = #{userId} AND status = 0")
    Long selectUnreadCount(Long userId);

    // 一键已读 (把该用户的所有消息状态改为1)
    @Update("UPDATE message SET status = 1 WHERE to_id = #{userId} AND status = 0")
    void readAll(Long userId);
}