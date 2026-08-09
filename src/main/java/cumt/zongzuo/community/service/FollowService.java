package cumt.zongzuo.community.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import cumt.zongzuo.community.entity.Follow;
import cumt.zongzuo.community.entity.User;

public interface FollowService extends IService<Follow> {
    // 关注 / 取消关注
    void follow(Long followerId, Long followedId);

    // 检查是否已关注
    boolean isFollowed(Long followerId, Long followedId);

    // 分页获取关注列表 (返回 User 信息)
    Page<User> getUserFollowings(Long userId, int pageNo, int pageSize);

    // 分页获取粉丝列表 (返回 User 信息)
    Page<User> getUserFans(Long userId, int pageNo, int pageSize);
    // 设置好友备注
    void updateRemark(Long userId, Long targetId, String remark, String description);
}