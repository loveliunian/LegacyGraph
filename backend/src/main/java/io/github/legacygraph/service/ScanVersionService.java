package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

@Service
public class ScanVersionService extends ServiceImpl<ScanVersionRepository, ScanVersion> {

    private final ScanTaskRepository scanTaskRepository;
    private final ScanVersionRepository scanVersionRepository;

    public ScanVersionService(ScanTaskRepository scanTaskRepository,
                              ScanVersionRepository scanVersionRepository) {
        this.scanTaskRepository = scanTaskRepository;
        this.scanVersionRepository = scanVersionRepository;
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

        scanVersionRepository.insert(version);
        return version;
    }

    /**
     * 获取扫描进度
     */
    public ScanProgressResponse getScanProgress(String versionId) {
        ScanVersion version = scanVersionRepository.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("扫描版本不存在: " + versionId);
        }

        LambdaQueryWrapper<ScanTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ScanTask::getVersionId, versionId);
        List<ScanTask> tasks = scanTaskRepository.selectList(wrapper);

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
        ScanVersion version = scanVersionRepository.selectById(versionId);
        if (version != null) {
            version.setScanStatus(status);
            if ("RUNNING".equals(status)) {
                version.setStartedAt(LocalDateTime.now());
            } else if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
                version.setFinishedAt(LocalDateTime.now());
            }
            version.setUpdatedAt(LocalDateTime.now());
            scanVersionRepository.updateById(version);
        }
    }

    /**
     * 删除扫描版本及其关联的扫描任务
     */
    @Transactional
    public void deleteScanVersion(String versionId) {
        // 先删除关联的扫描任务
        LambdaQueryWrapper<ScanTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ScanTask::getVersionId, versionId);
        scanTaskRepository.selectList(wrapper)
                .forEach(task -> scanTaskRepository.deleteById(task.getId()));
        // 再删除版本本身
        scanVersionRepository.deleteById(versionId);
    }
}
