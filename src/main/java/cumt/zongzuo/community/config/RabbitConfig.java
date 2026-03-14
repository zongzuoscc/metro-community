package cumt.zongzuo.community.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    // 原有的邮件队列
    @Bean
    public Queue mailQueue() {
        return new Queue("mail.queue", true);
    }

    // 【新增】消息通知队列
    @Bean
    public Queue notificationQueue() {
        return new Queue("message.notify.queue", true);
    }

    // JSON 转换器 (保持不变)
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
    @Bean
    public Queue commentTaskQueue() {
        return new Queue("comment.task.queue", true);
    }

    // 【新增】点赞任务队列
    @Bean
    public Queue likeQueue() {
        return new Queue("like.task.queue", true);
    }

    // 【新增】ES 文章数据同步队列
    @Bean
    public Queue esSyncQueue() {
        return new Queue("es.sync.queue", true);
    }

    // 【本次新增】AI 文章审核队列
    @Bean
    public Queue articleAuditQueue() {
        return new Queue("article.audit.queue", true);
    }
}