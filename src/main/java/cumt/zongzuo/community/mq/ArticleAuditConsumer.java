package cumt.zongzuo.community.mq;

import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.service.ArticleService;
import cumt.zongzuo.community.service.MetroAiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RabbitListener(queues = "article.audit.queue")
public class ArticleAuditConsumer {

    @Autowired
    private ArticleService articleService;

    @Autowired
    private MetroAiService metroAiService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @RabbitHandler
    public void handleAuditTask(Long articleId) {
        log.info("🔍 AI 开始审核文章 ID: {}", articleId);
        try {
            // 1. 获取文章最新状态
            Article article = articleService.getById(articleId);
            if (article == null || article.getStatus() != 2 || article.getIsDeleted() == 1) {
                log.info("文章 ID: {} 状态不为审核中或已删除，跳过审核", articleId);
                return;
            }

            // 2. 调用大模型进行内容安全判定
            String auditResult = metroAiService.auditContent(article.getTitle(), article.getContent());
            log.info("🤖 AI 审核返回结果: {}", auditResult);

            // 3. 根据结果更新状态
            if (auditResult.startsWith("PASS")) {
                // 审核通过
                article.setStatus(1);
                articleService.updateById(article);
                log.info("✅ 文章 ID: {} 审核通过，准许上架", articleId);

                // 【关键点】审核通过后，触发 ES 数据同步，让用户能够搜到！
                rabbitTemplate.convertAndSend("es.sync.queue", articleId);

            } else if (auditResult.startsWith("REJECT")) {
                // 审核被拒
                article.setStatus(3);
                articleService.updateById(article);
                log.warn("❌ 文章 ID: {} 审核不通过被拦截。原因: {}", articleId, auditResult);

                // 【可选优化】在这里可以通过你原有的 notificationQueue 给用户发一条私信，告知文章因违规被退回
                // rabbitTemplate.convertAndSend("message.notify.queue", ...);

            } else {
                // 如果 AI 宕机或返回了奇怪的东西，保持状态为 2 (人工审核兜底)
                log.warn("⚠️ 文章 ID: {} AI 审核异常，退回人工审核队列", articleId);
            }

            // 清理缓存
            articleService.auditArticle(articleId, article.getStatus() == 1, ""); // 复用清理缓存的逻辑

        } catch (Exception e) {
            log.error("AI 异步审核任务执行失败，文章 ID: {}", articleId, e);
        }
    }
}