package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.service.TagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tag")
public class TagController {

    @Autowired
    private TagService tagService;

    @GetMapping("/hot")
    public Result<List<String>> getHotTags() {
        return Result.success(tagService.getHotTags());
    }
}