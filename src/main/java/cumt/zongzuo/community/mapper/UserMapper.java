package cumt.zongzuo.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper // 一定要加这个注解，否则启动会报找不到 Mapper
public interface UserMapper extends BaseMapper<User> {
}
