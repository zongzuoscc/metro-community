package cumt.zongzuo.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.entity.Favorite;
import cumt.zongzuo.community.entity.FavoriteFolder;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.FavoriteFolderMapper;
import cumt.zongzuo.community.mapper.FavoriteMapper;
import cumt.zongzuo.community.service.FavoriteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FavoriteServiceImpl extends ServiceImpl<FavoriteFolderMapper, FavoriteFolder> implements FavoriteService {

    @Autowired
    private FavoriteMapper favoriteMapper;

    @Autowired
    private FavoriteFolderMapper favoriteFolderMapper;

    @Autowired
    private ArticleMapper articleMapper;

    @Override
    public void createFolder(Long userId, String name, String description, Integer isPublic) {
        FavoriteFolder folder = new FavoriteFolder();
        folder.setUserId(userId);
        folder.setName(name);
        folder.setDescription(description);
        folder.setIsPublic(isPublic);
        folder.setCreateTime(LocalDateTime.now());
        save(folder);
    }

    @Override
    public void createDefaultFolder(Long userId) {
        createFolder(userId, "默认收藏夹", "系统自动创建的默认收藏夹", 0); // 0-私密，1-公开，看你需求
    }

    @Override
    public List<FavoriteFolder> getUserFolders(Long userId) {
        // 调用我们刚才在 XML 里写的高性能查询
        return favoriteFolderMapper.selectUserFoldersWithCount(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void toggleFavorite(Long userId, Long articleId, Long folderId) {
        // 1. 如果 folderId 为 null，则查找该用户的“默认收藏夹”
        if (folderId == null) {
            FavoriteFolder defaultFolder = getOne(new QueryWrapper<FavoriteFolder>()
                    .eq("user_id", userId)
                    .eq("name", "默认收藏夹")
                    .last("limit 1"));
            if (defaultFolder == null) {
                // 防御性代码：万一老用户没有默认收藏夹，现创一个
                createDefaultFolder(userId);
                // 递归重试一次
                toggleFavorite(userId, articleId, null);
                return;
            }
            folderId = defaultFolder.getId();
        }

        // 2. 检查该文章是否已在这个收藏夹里
        QueryWrapper<Favorite> query = new QueryWrapper<>();
        query.eq("user_id", userId)
                .eq("article_id", articleId)
                .eq("folder_id", folderId);

        Favorite exist = favoriteMapper.selectOne(query);

        if (exist != null) {
            // --- 已存在 -> 取消收藏 ---
            favoriteMapper.deleteById(exist.getId());
        } else {
            // --- 不存在 -> 添加收藏 ---
            Favorite favorite = new Favorite();
            favorite.setUserId(userId);
            favorite.setArticleId(articleId);
            favorite.setFolderId(folderId);
            favorite.setCreateTime(LocalDateTime.now());
            favoriteMapper.insert(favorite);
        }
    }

    @Override
    public Boolean isCollected(Long userId, Long articleId) {
        // 只要在任何一个收藏夹里，就算收藏过
        Long count = favoriteMapper.selectCount(new QueryWrapper<Favorite>()
                .eq("user_id", userId)
                .eq("article_id", articleId));
        return count > 0;
    }

    // 【新增实现】严格的 Service 层业务逻辑
    @Override
    public Map<String, Object> getFolderDetail(Long folderId) {
        // 1. 校验收藏夹是否存在
        FavoriteFolder folder = favoriteFolderMapper.selectById(folderId);
        if (folder == null) {
            throw new RuntimeException("收藏夹不存在"); // 抛出异常，由 Controller 或全局异常处理捕获
        }

        // 2. 查询该收藏夹下的文章
        List<Article> articles = articleMapper.selectArticlesByFolderId(folderId);

        // 3. 组装数据
        Map<String, Object> map = new HashMap<>();
        map.put("folder", folder);
        map.put("articles", articles);

        return map;
    }
}