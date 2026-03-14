package cumt.zongzuo.community.mq;

import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.service.ArticleService;
import cumt.zongzuo.community.service.MetroAiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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

    @RabbitHandler
    public void handleAuditTask(Long articleId) {
        log.info("🔍 AI 开始审核文章 ID: {}", articleId);
        try {
            // 1. 获取文章最新状态
            Article article = articleService.getById(articleId);
            if (article == null || article.getStatus() != 2 || article.getIsDeleted() == 1) {
                return;
            }

            // 2. 调用大模型进行内容安全判定
            String auditResult = metroAiService.auditContent(article.getTitle(), article.getContent());
            log.info("🤖 AI 审核返回结果: {}", auditResult);

            // 3. 【神仙调用】直接复用 Service 里的审核方法！它会自动判断出这是 AI 调用的
            if (auditResult.startsWith("PASS")) {
                articleService.auditArticle(articleId, true, "AI审核通过");
            } else if (auditResult.startsWith("REJECT")) {
                String reason = auditResult.replace("REJECT:", "").trim();
                articleService.auditArticle(articleId, false, reason);
            } else {
                log.warn("⚠️ 文章 ID: {} AI 审核异常，退回人工审核队列", articleId);
            }

        } catch (Exception e) {
            log.error("AI 异步审核任务执行失败，文章 ID: {}", articleId, e);
        }
    }
}