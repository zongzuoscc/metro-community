package cumt.zongzuo.community.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.entity.Report;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.service.ReportService;
import cumt.zongzuo.community.service.UserService;
import cumt.zongzuo.community.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/report")
public class ReportController {

    @Autowired
    private ReportService reportService;
    @Autowired
    private UserService userService;

    /**
     * 用户提交举报
     */
    @PostMapping("/submit")
    public Result<String> submit(@RequestBody Map<String, Object> params, @RequestHeader("token") String token) {
        Long userId = JwtUtils.getUserId(token);
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
            @RequestParam(required = false) Integer status,
            @RequestHeader("token") String token) {

        checkAdmin(token); // 鉴权
        return Result.success(reportService.getReportList(page, size, status));
    }

    /**
     * 【管理员】处理举报
     */
    @PostMapping("/admin/process")
    public Result<String> process(@RequestBody Map<String, Object> params, @RequestHeader("token") String token) {
        Long adminId = JwtUtils.getUserId(token);
        checkAdmin(token);

        Long reportId = Long.valueOf(params.get("id").toString());
        boolean isViolation = Boolean.parseBoolean(params.get("isViolation").toString());
        String result = (String) params.get("result");

        reportService.processReport(adminId, reportId, isViolation, result);
        return Result.success("处理完成");
    }

    // 简单的管理员鉴权辅助方法
    private void checkAdmin(String token) {
        Long userId = JwtUtils.getUserId(token);
        User user = userService.getById(userId);
        if (user == null || user.getRole() != 1) {
            throw new RuntimeException("无权访问"); // 全局异常捕获会处理
        }
    }
}