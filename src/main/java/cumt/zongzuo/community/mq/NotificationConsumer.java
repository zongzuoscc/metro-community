package cumt.zongzuo.community.mq;

import cumt.zongzuo.community.dto.NotificationMsgDTO;
import cumt.zongzuo.community.service.MessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RabbitListener(queues = "message.notify.queue")
public class NotificationConsumer {

    @Autowired
    private MessageService messageService;

    @RabbitHandler
    public void handle(NotificationMsgDTO msg) {
        try {
            // 调用原有的 Service 方法入库
            // 注意：这里是异步线程执行，即便慢一点也不会卡住用户的前端请求
            messageService.send(
                    msg.getFromId(),
                    msg.getToId(),
                    msg.getType(),
                    msg.getTargetId(),
                    msg.getContent()
            );
        } catch (Exception e) {
            log.error("站内通知任务执行失败: {}", msg, e);
            throw new IllegalStateException("站内通知任务执行失败", e);
        }
    }
}
