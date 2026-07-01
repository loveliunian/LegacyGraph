package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.legacygraph.common.ErrorCode;
import io.github.legacygraph.dto.CreateScanVersionRequest;
import io.github.legacygraph.dto.ScanProgressResponse;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.exception.BusinessException;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ScanVersionService extends ServiceImpl<ScanVersionRepository, ScanVersion> {

    private final ScanTaskRepository scanTaskRepository;
    private final ScanVersionRepository scanVersionRepository;
    private final CacheService cacheService;

    /** 进度缓存 key 前缀 */
    private static final String PROGRESS_KEY = "scan:progress:";
    /** 运行中进度缓存 TTL（短，吸收高频轮询同时不长时间陈旧） */
    private static final Duration RUNNING_TTL = Duration.ofSeconds(3);
    /** 终态进度缓存 TTL（长，结果不再变化） */
    private static final Duration TERMINAL_TTL = Duration.ofMinutes(30);

    public ScanVersionService(ScanTaskRepository scanTaskRepository,
                              ScanVersionRepository scanVersionRepository,
                              CacheService cacheService) {
        this.scanTaskRepository = scanTaskRepository;
        this.scanVersionRepository = scanVersionRepository;
        this.cacheService = cacheService;
    }

    /** 版本号日期格式 */
    private static final DateTimeFormatter VERSION_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 生成不易重复的版本号：scan-YYYYMMDD-HHmmss-XXXX
     */
    private static String generateVersionNo() {
        String datePart = LocalDateTime.now().format(VERSION_DATE_FMT);
        String randPart = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
        return "scan-" + datePart + "-" + randPart;
    }

    /**
     * 创建扫描版本
     */
    @Transactional
    public ScanVersion createScanVersion(String projectId, CreateScanVersionRequest request) {
        // 版本号自动生成（空时兜底）
        String versionNo = request.getVersionNo();
        if (versionNo == null || versionNo.isBlank()) {
            versionNo = generateVersionNo();
        }

        // 检查同项目下版本号是否已存在
        LambdaQueryWrapper<ScanVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ScanVersion::getProjectId, projectId);
        wrapper.eq(ScanVersion::getVersionNo, versionNo);
        if (scanVersionRepository.selectCount(wrapper) > 0) {
            // 如果是自动生成的仍然碰撞（极端情况），重试一次
            if (request.getVersionNo() == null || request.getVersionNo().isBlank()) {
                versionNo = generateVersionNo() + "-r";
                wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(ScanVersion::getProjectId, projectId);
                wrapper.eq(ScanVersion::getVersionNo, versionNo);
                if (scanVersionRepository.selectCount(wrapper) > 0) {
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "版本号生成冲突，请重试");
                }
            } else {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "扫描版本已存在: " + request.getVersionNo());
            }
        }

        ScanVersion version = new ScanVersion();
        version.setProjectId(projectId);
        version.setVersionNo(versionNo);
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
     * 获取扫描进度（缓存优先：减轻前端高频轮询对 DB 的压力）。
     * 运行中以短 TTL 缓存吸收轮询峰值，终态以长 TTL 缓存。
     */
    public ScanProgressResponse getScanProgress(String versionId) {
        String cacheKey = PROGRESS_KEY + versionId;
        ScanProgressResponse cached = cacheService.get(cacheKey, ScanProgressResponse.class);
        if (cached != null) {
            return cached;
        }

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

        ScanProgressResponse response =
                new ScanProgressResponse(versionId, version.getScanStatus(), progress, taskProgressList);

        // 终态用长 TTL，运行中用短 TTL
        boolean terminal = "SUCCESS".equals(version.getScanStatus())
                || "FAILED".equals(version.getScanStatus())
                || "CANCELLED".equals(version.getScanStatus());
        cacheService.put(cacheKey, response, terminal ? TERMINAL_TTL : RUNNING_TTL);
        return response;
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
            // 状态变更后失效进度缓存，确保下次读取拿到最新状态
            cacheService.evict(PROGRESS_KEY + versionId);
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
        cacheService.evict(PROGRESS_KEY + versionId);
    }
}
