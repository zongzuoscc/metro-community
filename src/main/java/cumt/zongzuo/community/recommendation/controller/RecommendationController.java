package cumt.zongzuo.community.recommendation.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import cumt.zongzuo.community.recommendation.dto.RecommendationFeedResponse;
import cumt.zongzuo.community.recommendation.dto.RecommendationViewRequest;
import cumt.zongzuo.community.recommendation.service.RecommendationFeedService;
import cumt.zongzuo.community.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationFeedService feedService;
    private final RecommendationProperties properties;

    @GetMapping("/feed")
    public Result<RecommendationFeedResponse> feed(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        int requestedSize = size == null ? properties.getDefaultPageSize() : size;
        return Result.success(feedService.feed(CurrentUser.id(), cursor, requestedSize));
    }

    @PostMapping("/views/{articleId}")
    public Result<Void> view(@PathVariable Long articleId,
                             @RequestBody(required = false) RecommendationViewRequest request) {
        feedService.recordView(CurrentUser.id(), articleId, request);
        return Result.success();
    }
}
