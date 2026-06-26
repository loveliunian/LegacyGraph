package io.github.legacygraph.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.legacygraph.dto.CreateScanVersionRequest;
import io.github.legacygraph.dto.ScanProgressResponse;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ScanVersionService extends ServiceImpl<ScanVersionRepository, ScanVersion> {

    private final ScanTaskRepository scanTaskRepository;

    public ScanVersionService(ScanTaskRepository scanTaskRepository) {
        this.scanTaskRepository = scanTaskRepository;
    }

    /**
     * 创建扫描版本
     */
    @Transactional
    public ScanVersion createScanVersion(String projectId, CreateScanVersionRequest request) {
        ScanVersion version = new ScanVersion();
        version.setProjectId(projectId);
        version.setVersionNo(request.getVersionNo());
        version.setBranchName(request.getBranchName());
        version.setCommitId(request.getCommitId());
        version.setScanScope(request.getScanScope());
        version.setScanStatus("CREATED");
        version.setCreatedAt(LocalDateTime.now());
        version.setUpdatedAt(LocalDateTime.now());

        save(version);
        return version;
    }

    /**
     * 获取扫描进度
     */
    public ScanProgressResponse getScanProgress(String versionId) {
        ScanVersion version = getById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("扫描版本不存在: " + versionId);
        }

        List<ScanTask> tasks = scanTaskRepository.lambdaQuery()
                .eq(ScanTask::getVersionId, versionId)
                .list();

        List<ScanProgressResponse.TaskProgress> taskProgressList = new ArrayList<>();
        int totalTasks = tasks.size();
        int completedTasks = 0;

        for (ScanTask task : tasks) {
            ScanProgressResponse.TaskProgress tp = new ScanProgressResponse.TaskProgress();
            tp.setTaskType(task.getTaskType());
            tp.setStatus(task.getTaskStatus());
            tp.setFactCount(0); // 从outputSummary获取
            taskProgressList.add(tp);

            if ("SUCCESS".equals(task.getTaskStatus())) {
                completedTasks++;
            }
        }

        int progress = totalTasks > 0 ? (completedTasks * 100 / totalTasks) : 0;

        return new ScanProgressResponse(versionId, version.getScanStatus(), progress, taskProgressList);
    }

    /**
     * 更新扫描状态
     */
    @Transactional
    public void updateScanStatus(String versionId, String status) {
        ScanVersion version = getById(versionId);
        if (version != null) {
            version.setScanStatus(status);
            if ("RUNNING".equals(status)) {
                version.setStartedAt(LocalDateTime.now());
            } else if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
                version.setFinishedAt(LocalDateTime.now());
            }
            version.setUpdatedAt(LocalDateTime.now());
            updateById(version);
        }
    }
}
