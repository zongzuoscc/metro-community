package cumt.zongzuo.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.entity.Report;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ReportMapper extends BaseMapper<Report> {

    @Update("""
            UPDATE report
            SET status=#{status},handler_id=#{handlerId},handle_time=#{handledAt},result=#{result}
            WHERE id=#{reportId} AND status=0
            """)
    int updateDecisionIfPending(@Param("reportId") long reportId,
                                @Param("status") int status,
                                @Param("handlerId") long handlerId,
                                @Param("handledAt") LocalDateTime handledAt,
                                @Param("result") String result);
}
