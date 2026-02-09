package cumt.zongzuo.community.task;

import cumt.zongzuo.community.service.ArticleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ArticleTask {

    @Autowired
    private ArticleService articleService;

    // 每天凌晨 3 点执行一次
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanRecycleBin() {
        log.info("开始执行回收站清理任务...");
        articleService.cleanExpiredArticles();
        log.info("回收站清理完成");
    }
}