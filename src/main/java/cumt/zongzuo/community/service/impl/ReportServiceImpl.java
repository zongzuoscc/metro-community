package cumt.zongzuo.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import cumt.zongzuo.community.dto.NotificationMsgDTO;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.entity.Comment;
import cumt.zongzuo.community.entity.Report;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.CommentMapper;
import cumt.zongzuo.community.mapper.ReportMapper;
import cumt.zongzuo.community.service.ReportService;
import cumt.zongzuo.community.service.UserService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class ReportServiceImpl extends ServiceImpl<ReportMapper, Report> implements ReportService {

    @Autowired
    private UserService userService;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Override
    public void submitReport(Long userId, Long targetId, Integer targetType, String reason) {
        // 防止重复举报 (同一个目标，同一个用户，未处理状态)
        Long count = count(new QueryWrapper<Report>()
                .eq("reporter_id", userId)
                .eq("target_id", targetId)
                .eq("target_type", targetType)
                .eq("status", 0));
        if (count > 0) {
            throw new RuntimeException("您已举报过该内容，请耐心等待处理");
        }

        Report report = new Report();
        report.setReporterId(userId);
        report.setTargetId(targetId);
        report.setTargetType(targetType);
        report.setReason(reason);
        report.setStatus(0); // 待处理
        report.setCreateTime(LocalDateTime.now());
        save(report);
    }

    @Override
    public Page<Report> getReportList(int page, int size, Integer status) {
        Page<Report> pageInfo = new Page<>(page, size);
        QueryWrapper<Report> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByAsc("status"); // 待处理在前
        wrapper.orderByDesc("create_time");

        Page<Report> result = page(pageInfo, wrapper);

        // 填充详情 (举报人名、内容摘要)
        for (Report r : result.getRecords()) {
            User reporter = userService.getById(r.getReporterId());
            r.setReporterName(reporter != null ? reporter.getUsername() : "未知用户");

            // 填充被举报内容的快照
            fillSnapshot(r);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processReport(Long adminId, Long reportId, boolean isViolation, String result) {
        Report report = getById(reportId);
        if (report == null) throw new RuntimeException("举报记录不存在");

        String feedbackContent; // 定义通知内容

        if (isViolation) {
            report.setStatus(1); // 确认违规
            // 执行惩罚逻辑
            punishTarget(report.getTargetId(), report.getTargetType());
            // 【核心修改】针对文章，文案改为“退回修改”；针对评论，文案保持“删除”
            if (report.getTargetType() == 1) {
                feedbackContent = "【文章退回通知】您发布的文章被举报并经核实存在违规内容，现已被退回。请前往“个人中心”查看并修改，修改完成后可重新提交审核。处理备注：" + (result == null ? "无" : result);
            } else {
                feedbackContent = "【违规处理通知】您发布的评论因违反社区规范已被删除。请注意言行，共同维护社区环境。处理备注：" + (result == null ? "无" : result);
            }
        } else {
            report.setStatus(2); // 驳回
            feedbackContent = "【举报处理结果】您好，您举报的内容经核实未发现明显违规，暂不处理。如有疑问请联系管理员。处理备注：" + (result == null ? "无" : result);
        }

        report.setHandlerId(adminId);
        report.setHandleTime(LocalDateTime.now());
        report.setResult(result);
        updateById(report);

        sendSystemNotification(report.getReporterId(), report.getTargetId(), feedbackContent);
    }

    // 发送系统通知
    private void sendSystemNotification(Long toUserId, Long targetId, String content) {
        try {
            NotificationMsgDTO msg = new NotificationMsgDTO();
            // 确保数据库中有一个 ID=1 的管理员用户，或者改成其他存在的系统账号ID
            msg.setFromId(1L);
            msg.setToId(toUserId);
            msg.setType(0); // 0 代表系统通知
            msg.setTargetId(targetId);
            msg.setContent(content);

            rabbitTemplate.convertAndSend("message.notify.queue", msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 辅助：填充快照
    private void fillSnapshot(Report r) {
        if (r.getTargetType() == 1) { // 文章
            Article article = articleMapper.selectById(r.getTargetId());
            if (article != null) {
                r.setTargetSnapshot("[文章] " + article.getTitle());
            } else {
                r.setTargetSnapshot("[文章已删除]");
            }
        } else if (r.getTargetType() == 2) { // 评论
            // 即使评论被删除了，如果是逻辑删除，selectById 默认查不到
            // 如果需要展示已删除评论的内容，需要在 XML 手写 SQL 忽略 deleted 字段
            // 这里暂且保持现状，查不到就显示已删除
            Comment comment = commentMapper.selectById(r.getTargetId());
            if (comment != null) {
                String content = comment.getContent();
                r.setTargetSnapshot("[评论] " + (content.length() > 20 ? content.substring(0, 20) + "..." : content));
            } else {
                r.setTargetSnapshot("[评论已删除]");
            }
        } else if (r.getTargetType() == 3) { // 用户
            User user = userService.getById(r.getTargetId());
            r.setTargetSnapshot("[用户] " + (user != null ? user.getUsername() : "未知"));
        }
    }

    // 辅助：执行惩罚
    private void punishTarget(Long targetId, Integer targetType) {
        if (targetType == 1) { // 违规文章 -> 设为拒绝/违规下架
            Article article = articleMapper.selectById(targetId);
            if (article != null) {
                article.setStatus(3); // 状态 3 表示违规/未通过
                articleMapper.updateById(article);
            }
        } else if (targetType == 2) { // 违规评论 -> 逻辑删除
            // 【核心修复】对于 @TableLogic 注解的实体，必须使用 deleteById 才能触发逻辑删除更新
            // 之前的 updateById + setIsDeleted(1) 会被 MP 忽略
            commentMapper.deleteById(targetId);
        }
        // 违规用户暂不自动封号
    }
}