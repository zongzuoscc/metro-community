package cumt.zongzuo.community.mq;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

@Component
@RabbitListener(queues = "mail.queue")
public class MailConsumer {

    @Autowired
    private JavaMailSender mailSender;

    // 【新增】注入模板引擎
    @Autowired
    private TemplateEngine templateEngine;

    @Value("${spring.mail.username}")
    private String from;

    @RabbitHandler
    public void process(Map<String, String> map) {
        String email = map.get("email");
        String code = map.get("code");

        // 创建上下文对象，用于给模板传递数据
        Context context = new Context();
        context.setVariable("code", code); // 对应模板里的 ${code}
        context.setVariable("email", email);

        // 核心：解析 HTML 模板
        // "mail/verify" 对应 resources/templates/mail/verify.html
        String content = templateEngine.process("mail/verify", context);

        try {
            // 使用 MimeMessage 发送 HTML 邮件
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true); // true 表示支持多部分(附件/HTML)

            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("【Community】您的注册验证码");

            // 第二个参数 true 表示这是一段 HTML 代码
            helper.setText(content, true);

            mailSender.send(message);
            System.out.println("✅ HTML 邮件已发送给: " + email);
        } catch (MessagingException e) {
            System.err.println("❌ 邮件发送失败: " + e.getMessage());
        }
    }
}