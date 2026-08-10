package cumt.zongzuo.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.entity.FavoriteFolder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FavoriteFolderMapper extends BaseMapper<FavoriteFolder> {

    /**
     * 查询某用户的所有收藏夹，并统计每个收藏夹内的文章数量
     * 使用 XML 实现高性能 SQL
     */
    List<FavoriteFolder> selectUserFoldersWithCount(@Param("userId") Long userId);

    @Select("SELECT * FROM favorite_folder WHERE id=#{folderId} AND user_id=#{userId} LIMIT 1")
    FavoriteFolder selectOwnedById(@Param("folderId") Long folderId,
                                   @Param("userId") Long userId);
}
