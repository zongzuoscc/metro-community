package cumt.zongzuo.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import cumt.zongzuo.community.entity.Tag;
import cumt.zongzuo.community.mapper.TagMapper;
import cumt.zongzuo.community.service.TagService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TagServiceImpl extends ServiceImpl<TagMapper, Tag> implements TagService {

    @Override
    public List<String> getHotTags() {
        // 业务逻辑下沉到 Service
        return list(new QueryWrapper<Tag>()
                .orderByDesc("article_count")
                .last("limit 10"))
                .stream()
                .map(Tag::getName)
                .collect(Collectors.toList());
    }
}