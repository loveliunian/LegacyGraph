package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.repository.ScanTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 扫描任务记录器 — 统一 createTask/completeTask/logProgress。
 * <p>
 * 消除 ProjectScanner 与 AiScanOrchestrator 中的重复实现（见 B-M1）。
 * 所有扫描相关的子任务创建、进度记录、完成标记均通过此组件。
 * </p>
 */
@Slf4j
@Component
public class ScanTaskRecorder {

    private static final int PROGRESS_LOG_INTERVAL = 10;

    private final ScanTaskRepository scanTaskRepository;
    private final ObjectMapper objectMapper;

    public ScanTaskRecorder(ScanTaskRepository scanTaskRepository, ObjectMapper objectMapper) {
        this.scanTaskRepository = scanTaskRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建扫描子任务（状态 RUNNING）。
     */
    public ScanTask createTask(String projectId, String versionId, String taskType, String taskName) {
        ScanTask task = new ScanTask();
        task.setId(UUID.randomUUID().toString());
        task.setProjectId(projectId);
        task.setVersionId(versionId);
        task.setTaskType(taskType);
        task.setTaskName(taskName);
        task.setTaskStatus("RUNNING");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        scanTaskRepository.insert(task);
        log.info("Scan task started: projectId={}, versionId={}, taskType={}, taskName={}, taskId={}",
                projectId, versionId, taskType, taskName, task.getId());
        return task;
    }

    /**
     * 完成扫描子任务。
     *
     * @param task    子任务
     * @param summary 结果摘要（可空）
     * @param error   错误信息（null 表示成功）
     */
    public void completeTask(ScanTask task, String summary, String error) {
        try {
            task.setOutputSummary(objectMapper.writeValueAsString(summary));
        } catch (Exception e) {
            task.setOutputSummary("\"" + summary.replace("\"", "\\\"") + "\"");
        }
        task.setErrorMessage(error);
        task.setTaskStatus(error == null ? "SUCCESS" : "FAILED");
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        scanTaskRepository.updateById(task);
        log.info("Scan task completed: projectId={}, versionId={}, taskType={}, taskName={}, taskId={}, status={}",
                task.getProjectId(), task.getVersionId(), task.getTaskType(), task.getTaskName(),
                task.getId(), task.getTaskStatus());
    }

    /**
     * 记录子任务进度（日志级，非持久化）。
     * 按 PROGRESS_LOG_INTERVAL 采样输出，减少日志噪音。
     *
     * @param task      关联的子任务
     * @param processed 已处理数
     * @param total     总数
     * @param unit      数据单位名称（如 "files", "database connections"）
     */
    public void logProgress(ScanTask task, int processed, int total, String unit) {
        if (task == null) {
            return;
        }
        if (total <= 0) {
            log.info("Scan still running: projectId={}, versionId={}, taskType={}, taskName={}, detail=found 0 {}",
                    task.getProjectId(), task.getVersionId(), task.getTaskType(), task.getTaskName(), unit);
            return;
        }
        if (processed == 0 || processed == 1 || processed == total || processed % PROGRESS_LOG_INTERVAL == 0) {
            log.info("Scan still running: projectId={}, versionId={}, taskType={}, taskName={}, progress={}/{}, unit={}",
                    task.getProjectId(), task.getVersionId(), task.getTaskType(), task.getTaskName(),
                    processed, total, unit);
        }
    }
}
