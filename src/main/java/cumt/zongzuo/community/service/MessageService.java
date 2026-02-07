package cumt.zongzuo.community.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import cumt.zongzuo.community.entity.Message;

public interface MessageService extends IService<Message> {

    /**
     * 发送通知 (供其他Service调用)
     * @param fromId 发送者
     * @param toId 接收者
     * @param type 类型
     * @param targetId 目标ID
     * @param content 内容
     */
    void send(Long fromId, Long toId, Integer type, Long targetId, String content);

    /**
     * 获取我的消息列表 (分页)
     */
    Page<Message> getMyMessages(Long userId, int page, int size);

    /**
     * 获取未读数量
     */
    Long getUnreadCount(Long userId);

    /**
     * 一键已读
     */
    void readAll(Long userId);
}