package cumt.zongzuo.community.task;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.service.ArticleService;
import cumt.zongzuo.community.service.impl.ArticleServiceImpl; // 引入Service以获取常量Key
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class ViewCountSyncTask {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ArticleService articleService;

    // 每分钟执行一次
    @Scheduled(cron = "0 0/1 * * * ?")
    public void syncViewCountToDatabase() {
        // 1. 获取所有脏数据ID
        // 注意：这里引用了 ServiceImpl 中的常量，确保那个常量是 public 的
        Set<String> dirtyIds = stringRedisTemplate.opsForSet().members(ArticleServiceImpl.ARTICLE_VIEW_DIRTY_SET);

        if (dirtyIds == null || dirtyIds.isEmpty()) {
            return;
        }

        for (String idStr : dirtyIds) {
            try {
                Long articleId = Long.parseLong(idStr);
                // 拼接 Redis Key
                String viewCountKey = "article:view:count:" + articleId;
                String viewCountStr = stringRedisTemplate.opsForValue().get(viewCountKey);

                if (viewCountStr != null) {
                    // 更新数据库
                    articleService.update(new UpdateWrapper<Article>()
                            .eq("id", articleId)
                            .set("view_count", Integer.parseInt(viewCountStr)));

                    // 同步成功后，从脏集合中移除
                    stringRedisTemplate.opsForSet().remove(ArticleServiceImpl.ARTICLE_VIEW_DIRTY_SET, idStr);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}