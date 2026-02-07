package cumt.zongzuo.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.entity.ChatMsg;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChatMsgMapper extends BaseMapper<ChatMsg> {

    // 统计私信未读数
    @Select("SELECT COUNT(*) FROM chat_msg WHERE to_id = #{userId} AND status = 0")
    Long selectUnreadCount(Long userId);
}