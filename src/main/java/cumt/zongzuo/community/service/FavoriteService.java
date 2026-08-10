package cumt.zongzuo.community.service;

import com.baomidou.mybatisplus.spring.service.IService;
import cumt.zongzuo.community.entity.FavoriteFolder;

import java.util.List;
import java.util.Map;

public interface FavoriteService extends IService<FavoriteFolder> {

    /**
     * 创建一个收藏夹
     */
    void createFolder(Long userId, String name, String description, Integer isPublic);

    /**
     * 为用户创建默认收藏夹 (注册时调用)
     */
    void createDefaultFolder(Long userId);

    /**
     * 获取用户的所有收藏夹 (带文章数量统计)
     */
    List<FavoriteFolder> getUserFolders(Long userId);

    /**
     * 收藏 / 取消收藏文章
     * @param userId 当前用户
     * @param articleId 文章ID
     * @param folderId 放入哪个收藏夹 (如果为null，则尝试放入默认收藏夹)
     */
    void toggleFavorite(Long userId, Long articleId, Long folderId);

    /**
     * 检查文章是否被收藏 (用于前端回显亮星)
     */
    Boolean isCollected(Long userId, Long articleId);

    // 【新增】获取收藏夹详情 (业务逻辑：查信息 + 查文章 + 组装)
    Map<String, Object> getFolderDetail(Long folderId, Long currentUserId);
}
