package cumt.zongzuo.community.service;

import com.baomidou.mybatisplus.extension.service.IService;
import cumt.zongzuo.community.entity.Tag;
import java.util.List;

public interface TagService extends IService<Tag> {

    /**
     * 获取全站热门标签 (Top 10)
     * @return 标签名列表
     */
    List<String> getHotTags();
}