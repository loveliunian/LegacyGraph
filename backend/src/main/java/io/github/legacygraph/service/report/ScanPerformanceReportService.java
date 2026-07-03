package io.github.legacygraph.service.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描性能报告服务 — 为已完成/运行中的扫描版本生成 Markdown 格式的性能报告。
 */
@Slf4j
@Service
public class ScanPerformanceReportService {

    private final ScanVersionRepository scanVersionRepository;
    private final ScanTaskRepository scanTaskRepository;

    public ScanPerformanceReportService(ScanVersionRepository scanVersionRepository,
                                        ScanTaskRepository scanTaskRepository) {
        this.scanVersionRepository = scanVersionRepository;
        this.scanTaskRepository = scanTaskRepository;
    }

    /**
     * 生成 Markdown 格式的扫描性能报告。
     */
    public String generateMarkdown(String projectId, String versionId) {
        ScanVersion version = scanVersionRepository.selectById(versionId);
        if (version == null) {
            return "Version not found: " + versionId;
        }

        List<ScanTask> tasks = scanTaskRepository.selectList(new LambdaQueryWrapper<ScanTask>()
                .eq(ScanTask::getVersionId, versionId)
                .orderByAsc(ScanTask::getCreatedAt));

        StringBuilder sb = new StringBuilder();
        sb.append("# 扫描性能报告\n\n");
        sb.append("**版本号**: ").append(version.getVersionNo()).append("\n");
        sb.append("**状态**: ").append(version.getScanStatus()).append("\n");
        sb.append("**扫描范围**: ").append(version.getScanScope() != null ? version.getScanScope() : "全部").append("\n\n");

        // 总耗时
        Duration totalDuration = null;
        if (version.getStartedAt() != null && version.getFinishedAt() != null) {
            totalDuration = Duration.between(version.getStartedAt(), version.getFinishedAt());
        } else if (version.getStartedAt() != null) {
            totalDuration = Duration.between(version.getStartedAt(), java.time.LocalDateTime.now());
        }
        if (totalDuration != null) {
            sb.append("**总耗时**: ").append(formatDuration(totalDuration)).append("\n\n");
        }

        // 阶段详情表格
        sb.append("## 阶段详情\n\n");
        sb.append("| 阶段 | 状态 | 总项 | 已处理 | 开始时间 | 结束时间 | 耗时 |\n");
        sb.append("|------|------|------|--------|----------|----------|------|\n");

        int warningCount = 0;
        int failedCount = 0;
        int totalProcessed = 0;

        for (ScanTask task : tasks) {
            String taskDuration = "-";
            if (task.getStartedAt() != null && task.getFinishedAt() != null) {
                taskDuration = formatDuration(Duration.between(task.getStartedAt(), task.getFinishedAt()));
            }

            sb.append("| ").append(task.getTaskType())
                    .append(" | ").append(task.getTaskStatus())
                    .append(" | ").append(task.getTotalItems() != null ? task.getTotalItems() : "-")
                    .append(" | ").append(task.getProcessedItems() != null ? task.getProcessedItems() : "-")
                    .append(" | ").append(task.getStartedAt() != null ? task.getStartedAt().toString() : "-")
                    .append(" | ").append(task.getFinishedAt() != null ? task.getFinishedAt().toString() : "-")
                    .append(" | ").append(taskDuration)
                    .append(" |\n");

            if ("WARNING".equals(task.getTaskStatus())) warningCount++;
            if ("FAILED".equals(task.getTaskStatus())) failedCount++;
            if (task.getProcessedItems() != null) totalProcessed += task.getProcessedItems();
        }

        sb.append("\n## 汇总\n\n");
        sb.append("- 阶段总数: ").append(tasks.size()).append("\n");
        sb.append("- 警告数: ").append(warningCount).append("\n");
        sb.append("- 失败数: ").append(failedCount).append("\n");
        sb.append("- 总处理项: ").append(totalProcessed).append("\n");

        // 警告详情
        if (warningCount > 0) {
            sb.append("\n## 警告详情\n\n");
            for (ScanTask task : tasks) {
                if ("WARNING".equals(task.getTaskStatus())) {
                    sb.append("- **").append(task.getTaskType()).append("**: ")
                            .append(task.getOutputSummary() != null ? task.getOutputSummary() : task.getErrorMessage() != null ? task.getErrorMessage() : "-")
                            .append("\n");
                }
            }
        }

        // 失败详情
        if (failedCount > 0) {
            sb.append("\n## 失败详情\n\n");
            for (ScanTask task : tasks) {
                if ("FAILED".equals(task.getTaskStatus())) {
                    sb.append("- **").append(task.getTaskType()).append("**: ")
                            .append(task.getErrorMessage() != null ? task.getErrorMessage() : "-")
                            .append("\n");
                }
            }
        }

        return sb.toString();
    }

    private String formatDuration(Duration d) {
        long s = d.getSeconds();
        if (s < 60) return s + "s";
        long m = s / 60;
        long sec = s % 60;
        if (m < 60) return m + "m" + sec + "s";
        long h = m / 60;
        m = m % 60;
        return h + "h" + m + "m" + sec + "s";
    }
}
