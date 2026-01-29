package cumt.zongzuo.community.mq;

import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@RabbitListener(queues = "mail.queue") // 监听刚才定义的队列
public class MailConsumer {

    @Autowired
    private JavaMailSender mailSender;

    // 获取配置文件里的发送人邮箱
    @Value("${spring.mail.username}")
    private String from;

    @RabbitHandler
    public void process(Map<String, String> map) {
        String email = map.get("email");
        String code = map.get("code");

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(email);
            // 修改邮件标题和正文，使其看起来更正式
            message.setSubject("【Community社区】账号安全验证"); // 不要写“测试”
            message.setText("尊敬的用户您好：\n\n您正在注册 Community 开发者社区，您的验证码是：" + code + "。\n\n该验证码 5 分钟内有效，请尽快完成注册。\n如果这不是您本人的操作，请忽略此邮件。");

            mailSender.send(message);
            System.out.println("✅ 邮件已发送给: " + email);
        } catch (Exception e) {
            System.err.println("❌ 邮件发送失败: " + e.getMessage());
            // 实际生产中这里可能需要重试机制
        }
    }
}