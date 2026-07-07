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
        task.setStartedAt(LocalDateTime.now());
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
            // 先转义反斜杠，再转义双引号（顺序不能颠倒，否则会二次转义）
            task.setOutputSummary("\"" + summary.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
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
     * 完成扫描子任务（支持非 SUCCESS/FAILED 终态，如 WARNING/SKIPPED）。
     *
     * @param task           子任务
     * @param summary        结果摘要（可空）
     * @param error          错误信息（null 表示非失败）
     * @param terminalStatus 终态状态（null 时自动推导：error==null → SUCCESS，否则 → FAILED）
     */
    public void completeTask(ScanTask task, String summary, String error, String terminalStatus) {
        if (task == null) {
            return;
        }
        try {
            if (summary != null) {
                task.setOutputSummary(objectMapper.writeValueAsString(summary));
            }
        } catch (Exception e) {
            // 先转义反斜杠，再转义双引号（顺序不能颠倒，否则会二次转义）
            task.setOutputSummary("\"" + (summary != null ? summary.replace("\\", "\\\\").replace("\"", "\\\"") : "") + "\"");
        }
        task.setErrorMessage(error);
        String finalStatus = terminalStatus != null ? terminalStatus
                : (error == null ? "SUCCESS" : "FAILED");
        task.setTaskStatus(finalStatus);
        task.setFinishedAt(LocalDateTime.now());

        // 修复进度百分比：SUCCESS/WARNING 时强制 processedItems = totalItems
        // 解决"83%显示已完成""60%显示已完成"等问题（截断前总数 > 实际处理数）
        if ("SUCCESS".equals(finalStatus) || "WARNING".equals(finalStatus)) {
            Integer total = task.getTotalItems();
            if (total != null && total > 0) {
                task.setProcessedItems(total);
            }
        }

        task.setUpdatedAt(LocalDateTime.now());
        scanTaskRepository.updateById(task);
        log.info("Scan task completed: projectId={}, versionId={}, taskType={}, taskName={}, taskId={}, status={}",
                task.getProjectId(), task.getVersionId(), task.getTaskType(), task.getTaskName(),
                task.getId(), task.getTaskStatus());
    }

    /** 进度更新批量阈值：每处理 PROGRESS_DB_BATCH_SIZE 项才写一次 DB */
    private static final int PROGRESS_DB_BATCH_SIZE = 50;
    
    /** 进度更新最小时间间隔（毫秒）：避免高频写库 */
    private static final long PROGRESS_DB_MIN_INTERVAL_MS = 1000;
    
    /** 上次 DB 更新时间戳（按 taskId 索引） */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastDbUpdateTime = 
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 记录子任务进度（日志级，不写 DB）。
     * 按 PROGRESS_LOG_INTERVAL 采样输出，减少日志噪音。
     *
     * @param task      关联的子任务
     * @param processed 已处理数
     * @param total     总数
     * @param unit      数据单位名称
     */
    public void logProgress(ScanTask task, int processed, int total, String unit) {
        logProgress(task, processed, total, unit, null);
    }

    /**
     * 记录子任务进度并同步到 DB（供前端轮询读取）。
     * <p>
     * <b>性能优化：</b>采用批量写库策略，每处理 {@link #PROGRESS_DB_BATCH_SIZE} 项或
     * 超过 {@link #PROGRESS_DB_MIN_INTERVAL_MS} 毫秒才写一次 DB，减少 90%+ 的 DB 写入。
     * </p>
     *
     * @param task           关联的子任务
     * @param processed      已处理数
     * @param total          总数
     * @param unit           数据单位名称（如 "files", "database connections"）
     * @param currentItem    当前处理项名称（可空）
     */
    public void logProgress(ScanTask task, int processed, int total, String unit, String currentItem) {
        if (task == null) {
            return;
        }
        if (total <= 0) {
            log.info("Scan still running: projectId={}, versionId={}, taskType={}, taskName={}, detail=found 0 {}",
                    task.getProjectId(), task.getVersionId(), task.getTaskType(), task.getTaskName(), unit);
            return;
        }
        // 日志采样：每 PROGRESS_LOG_INTERVAL 项或关键节点输出
        if (processed == 0 || processed == 1 || processed == total || processed % PROGRESS_LOG_INTERVAL == 0) {
            log.info("Scan still running: projectId={}, versionId={}, taskType={}, taskName={}, progress={}/{}, unit={}",
                    task.getProjectId(), task.getVersionId(), task.getTaskType(), task.getTaskName(),
                    processed, total, unit);
        }
        
        // 性能优化：批量写库策略
        // 仅在以下情况写 DB：
        // 1. 达到批量阈值（每 PROGRESS_DB_BATCH_SIZE 项）
        // 2. 处理完成（processed == total）
        // 3. 首次更新（processed == 0 或 1）
        // 4. 超过最小时间间隔
        boolean shouldWriteToDb = processed == 0 
                || processed == 1 
                || processed == total 
                || processed % PROGRESS_DB_BATCH_SIZE == 0;
        
        if (!shouldWriteToDb) {
            // 检查时间间隔
            Long lastTime = lastDbUpdateTime.get(task.getId());
            long now = System.currentTimeMillis();
            if (lastTime != null && (now - lastTime) < PROGRESS_DB_MIN_INTERVAL_MS) {
                return; // 跳过本次 DB 写入
            }
        }
        
        // 同步更新 DB 进度字段
        try {
            task.setTotalItems(total);
            task.setProcessedItems(processed);
            if (currentItem != null) {
                task.setCurrentItem(currentItem);
            }
            task.setUpdatedAt(LocalDateTime.now());
            scanTaskRepository.updateById(task);
            lastDbUpdateTime.put(task.getId(), System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("Failed to update task progress for {}: {}", task.getTaskType(), e.getMessage());
        }
    }
}
