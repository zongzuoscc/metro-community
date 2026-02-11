package cumt.zongzuo.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.entity.Comment;
import cumt.zongzuo.community.entity.Report;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.CommentMapper;
import cumt.zongzuo.community.mapper.ReportMapper;
import cumt.zongzuo.community.service.ReportService;
import cumt.zongzuo.community.service.UserService;
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

        if (isViolation) {
            report.setStatus(1); // 确认违规
            // 执行惩罚逻辑
            punishTarget(report.getTargetId(), report.getTargetType());
        } else {
            report.setStatus(2); // 驳回
        }

        report.setHandlerId(adminId);
        report.setHandleTime(LocalDateTime.now());
        report.setResult(result);
        updateById(report);
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
        if (targetType == 1) { // 违规文章 -> 设为拒绝/删除
            Article article = articleMapper.selectById(targetId);
            if (article != null) {
                article.setStatus(3); // 3=拒绝/违规下架
                articleMapper.updateById(article);
            }
        } else if (targetType == 2) { // 违规评论 -> 逻辑删除
            Comment comment = commentMapper.selectById(targetId);
            if (comment != null) {
                comment.setIsDeleted(1);
                commentMapper.updateById(comment);
            }
        }
        // 违规用户暂不自动封号，由管理员在用户管理界面手动操作
    }
}