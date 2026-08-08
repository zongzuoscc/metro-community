package cumt.zongzuo.community.mq;

import cumt.zongzuo.community.dto.CommentTaskDTO;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.mapper.ArticleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RabbitListener(queues = "comment.task.queue")
public class CommentConsumer {

    @Autowired
    private ArticleMapper articleMapper;

    @RabbitHandler
    public void handle(CommentTaskDTO msg) {
        try {
            Long articleId = msg.getArticleId();
            if (articleId == null) return;

            // 默认数量为 1 (兼容旧逻辑)
            int changeCount = (msg.getCount() == null || msg.getCount() <= 0) ? 1 : msg.getCount();

            Article article = articleMapper.selectById(articleId);
            if (article != null) {
                int currentCount = article.getCommentCount() == null ? 0 : article.getCommentCount();

                if (msg.isAdd()) {
                    // 增加
                    article.setCommentCount(currentCount + changeCount);
                } else {
                    // 减少 (防止减成负数)
                    article.setCommentCount(Math.max(0, currentCount - changeCount));
                }

                articleMapper.updateById(article);
            }
        } catch (Exception e) {
            log.error("评论数异步更新失败: {}", msg, e);
            throw new IllegalStateException("评论计数任务执行失败", e);
        }
    }
}
