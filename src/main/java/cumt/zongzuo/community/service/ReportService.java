package cumt.zongzuo.community.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import cumt.zongzuo.community.entity.Report;

public interface ReportService extends IService<Report> {
    // 提交举报
    void submitReport(Long userId, Long targetId, Integer targetType, String reason);

    // 获取举报列表 (管理员)
    Page<Report> getReportList(int page, int size, Integer status);

    // 处理举报 (管理员)
    void processReport(Long adminId, Long reportId, boolean isViolation, String result);
}