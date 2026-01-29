package cumt.zongzuo.community.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter; // 引入这个包
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    public Queue mailQueue() {
        return new Queue("mail.queue", true);
    }

    // 【新增】配置 JSON 消息转换器
    // 这样发消息时会自动把 Map 转成 JSON 字符串
    // 收消息时会自动把 JSON 字符串转回 Map
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}