package cumt.zongzuo.community.service;

import com.baomidou.mybatisplus.extension.service.IService;
import cumt.zongzuo.community.entity.LikeRecord;

public interface LikeService extends IService<LikeRecord> {

    // 点赞 / 取消点赞 (自动判断)
    void like(Long userId, Long targetId, Integer targetType);

    // 查询当前用户是否已点赞
    boolean isLiked(Long userId, Long targetId, Integer targetType);
}