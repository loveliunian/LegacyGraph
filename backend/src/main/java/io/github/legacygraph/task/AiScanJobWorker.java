package io.github.legacygraph.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.entity.AiScanJob;
import io.github.legacygraph.repository.AiScanJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 扫描任务 Worker — 定期拉取 PENDING job 并异步执行 AI 编排。
 */
@Slf4j
@Component
public class AiScanJobWorker {

    private final AiScanJobRepository aiScanJobRepository;
    private final AiScanOrchestrator aiScanOrchestrator;
    private final ObjectMapper objectMapper;
    private final ProjectScanner projectScanner;
    private final io.github.legacygraph.repository.ScanVersionRepository scanVersionRepository;

    public AiScanJobWorker(AiScanJobRepository aiScanJobRepository,
                           AiScanOrchestrator aiScanOrchestrator,
                           ObjectMapper objectMapper,
                           ProjectScanner projectScanner,
                           io.github.legacygraph.repository.ScanVersionRepository scanVersionRepository) {
        this.aiScanJobRepository = aiScanJobRepository;
        this.aiScanOrchestrator = aiScanOrchestrator;
        this.objectMapper = objectMapper;
        this.projectScanner = projectScanner;
        this.scanVersionRepository = scanVersionRepository;
    }

    /**
     * 每 15 秒拉取一个 PENDING job 执行。只处理最早创建的一个，避免并发执行导致同一个版本的 AI job 重复运行。
     */
    @Scheduled(fixedDelay = 15_000)
    public void processPendingJobs() {
        List<AiScanJob> pendingJobs = aiScanJobRepository.selectList(new LambdaQueryWrapper<AiScanJob>()
                .eq(AiScanJob::getStatus, "PENDING")
                .orderByAsc(AiScanJob::getCreatedAt)
                .last("LIMIT 1"));

        if (pendingJobs.isEmpty()) {
            return;
        }

        AiScanJob job = pendingJobs.get(0);
        // 设置为 RUNNING 防止重复取
        job.setStatus("RUNNING");
        job.setStartedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        aiScanJobRepository.updateById(job);

        log.info("AI scan job started: jobId={}, projectId={}, versionId={}",
                job.getId(), job.getProjectId(), job.getVersionId());

        try {
            AiScanConfig config = parseConfig(job.getConfigJson());
            aiScanOrchestrator.orchestrate(job.getProjectId(), job.getVersionId(), config, () -> {
                // 检查 job 是否被取消
                AiScanJob current = aiScanJobRepository.selectById(job.getId());
                return current != null && "CANCELLED".equals(current.getStatus());
            });

            job.setStatus("SUCCESS");
            log.info("AI scan job completed: jobId={}", job.getId());
            
            // AI 任务完成后，刷新 ScanVersion 的统计数据（节点/边/事实数量）
            try {
                io.github.legacygraph.entity.ScanVersion version = scanVersionRepository.selectById(job.getVersionId());
                if (version != null) {
                    projectScanner.applyStatsSnapshot(version, job.getProjectId(), job.getVersionId());
                    scanVersionRepository.updateById(version);
                    log.info("ScanVersion stats refreshed after AI job: versionId={}, nodeCount={}", 
                            job.getVersionId(), version.getNodeCount());
                }
            } catch (Exception statEx) {
                log.warn("Failed to refresh ScanVersion stats after AI job: versionId={}, error={}", 
                        job.getVersionId(), statEx.getMessage());
            }
        } catch (Exception e) {
            log.error("AI scan job failed: jobId={}, error={}", job.getId(), e.getMessage(), e);
            job.setStatus("FAILED");
            job.setErrorMessage(e.getMessage());
        }

        job.setFinishedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        aiScanJobRepository.updateById(job);
    }

    private AiScanConfig parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return new AiScanConfig();
        }
        try {
            return objectMapper.readValue(configJson, AiScanConfig.class);
        } catch (Exception e) {
            log.warn("Failed to parse AI scan job config, using defaults: {}", e.getMessage());
            return new AiScanConfig();
        }
    }
}
