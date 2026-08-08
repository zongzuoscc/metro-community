package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.entity.FavoriteFolder;
import cumt.zongzuo.community.service.FavoriteService;
import cumt.zongzuo.community.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/favorite")
public class FavoriteController {

    @Autowired
    private FavoriteService favoriteService;

    // 1. 获取我的收藏夹列表 (带文章数量)
    @GetMapping("/list")
    public Result<List<FavoriteFolder>> getMyFolders() {
        Long userId = CurrentUser.id();
        List<FavoriteFolder> list = favoriteService.getUserFolders(userId);
        return Result.success(list);
    }

    // 2. 创建新收藏夹
    @PostMapping("/folder")
    public Result<String> createFolder(@RequestParam String name,
                                       @RequestParam(defaultValue = "") String description,
                                       @RequestParam(defaultValue = "1") Integer isPublic) {
        Long userId = CurrentUser.id();
        favoriteService.createFolder(userId, name, description, isPublic);
        return Result.success("创建成功");
    }

    // 3. 收藏 / 取消收藏
    // folderId 可选：如果不传，Service 层会自动放入“默认收藏夹”
    @PostMapping("/toggle")
    public Result<String> toggle(@RequestParam Long articleId,
                                 @RequestParam(required = false) Long folderId) {
        Long userId = CurrentUser.id();
        favoriteService.toggleFavorite(userId, articleId, folderId);
        return Result.success("操作成功");
    }

    // 4. 检查文章是否已收藏 (用于前端回显按钮颜色)
    @GetMapping("/check")
    public Result<Boolean> checkCollected(@RequestParam Long articleId) {
        Long userId = CurrentUser.id();
        Boolean isCollected = favoriteService.isCollected(userId, articleId);
        return Result.success(isCollected);
    }

    // 逻辑完全委托给 Service，Controller 只负责捕获异常和返回
    @GetMapping("/detail/{folderId}")
    public Result<Map<String, Object>> getFolderDetail(@PathVariable Long folderId) {
        try {
            Map<String, Object> detail = favoriteService.getFolderDetail(folderId);
            return Result.success(detail);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
