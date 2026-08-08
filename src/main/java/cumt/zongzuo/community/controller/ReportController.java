package cumt.zongzuo.community.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.entity.Report;
import cumt.zongzuo.community.service.ReportService;
import cumt.zongzuo.community.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/report")
public class ReportController {

    @Autowired
    private ReportService reportService;
    /**
     * 用户提交举报
     */
    @PostMapping("/submit")
    public Result<String> submit(@RequestBody Map<String, Object> params) {
        Long userId = CurrentUser.id();
        Long targetId = Long.valueOf(params.get("targetId").toString());
        Integer targetType = Integer.valueOf(params.get("targetType").toString());
        String reason = (String) params.get("reason");

        reportService.submitReport(userId, targetId, targetType, reason);
        return Result.success("举报已提交，我们会尽快处理");
    }

    /**
     * 【管理员】获取举报列表
     */
    @GetMapping("/admin/list")
    public Result<Page<Report>> getList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer status) {
        return Result.success(reportService.getReportList(page, size, status));
    }

    /**
     * 【管理员】处理举报
     */
    @PostMapping("/admin/process")
    public Result<String> process(@RequestBody Map<String, Object> params) {
        Long adminId = CurrentUser.id();

        Long reportId = Long.valueOf(params.get("id").toString());
        boolean isViolation = Boolean.parseBoolean(params.get("isViolation").toString());
        String result = (String) params.get("result");

        reportService.processReport(adminId, reportId, isViolation, result);
        return Result.success("处理完成");
    }
}
