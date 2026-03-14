package cumt.zongzuo.community.mq;

import cumt.zongzuo.community.document.ArticleDoc;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.repository.ArticleRepository;
import cumt.zongzuo.community.service.ArticleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RabbitListener(queues = "es.sync.queue") // 监听我们刚刚创建的队列
public class EsSyncConsumer {

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleRepository articleRepository;

    @RabbitHandler
    public void handleSyncMessage(Long articleId) {
        log.info("接收到 ES 同步任务，文章 ID: {}", articleId);

        try {
            // 1. 去 MySQL 里查出最新的文章状态
            Article article = articleService.getById(articleId);

            // 2. 状态判断：如果文章被物理删除，或者逻辑删除(is_deleted=1)，或者状态不是“已发布(status=1)”
            if (article == null || article.getIsDeleted() == 1 || article.getStatus() != 1) {
                // 将其从 ES 中移除，防止用户搜到被删掉或正在审核的文章
                articleRepository.deleteById(articleId);
                log.info("文章 ID: {} 已从 ES 中移除", articleId);
                return;
            }

            // 3. 如果文章正常且已发布，则更新或新增到 ES
            ArticleDoc doc = new ArticleDoc();
            doc.setId(article.getId());
            doc.setTitle(article.getTitle());
            doc.setContent(article.getContent());
            doc.setSummary(article.getSummary());
            doc.setCover(article.getCover());
            doc.setAuthorId(article.getAuthorId() != null ? article.getAuthorId() : 0L);
            doc.setViewCount(article.getViewCount() != null ? article.getViewCount() : 0);
            doc.setLikeCount(article.getLikeCount() != null ? article.getLikeCount() : 0);
            doc.setCommentCount(article.getCommentCount() != null ? article.getCommentCount() : 0);
            doc.setCollectCount(article.getCollectCount() != null ? article.getCollectCount() : 0);
            doc.setCreateTime(article.getCreateTime());

            // save 方法如果 ID 存在就会覆盖更新，不存在就会新增
            articleRepository.save(doc);
            log.info("文章 ID: {} 成功同步至 ES", articleId);

        } catch (Exception e) {
            log.error("ES 同步任务执行失败，文章 ID: {}", articleId, e);
        }
    }
}