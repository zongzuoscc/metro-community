package cumt.zongzuo.community.service;

import com.baomidou.mybatisplus.extension.service.IService;
import cumt.zongzuo.community.entity.Follow;

public interface FollowService extends IService<Follow> {
    // 关注 / 取消关注
    void follow(Long followerId, Long followedId);

    // 检查是否已关注
    boolean isFollowed(Long followerId, Long followedId);
}