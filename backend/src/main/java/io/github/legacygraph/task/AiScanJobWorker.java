package io.github.legacygraph.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.entity.AiScanJob;
import io.github.legacygraph.repository.AiScanJobRepository;
import io.github.legacygraph.service.systemoverview.SystemOverviewDocumentService;
import io.github.legacygraph.service.systemoverview.SystemOverviewIngestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private SystemOverviewDocumentService systemOverviewDocumentService;
    private SystemOverviewIngestService systemOverviewIngestService;

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

    @Autowired(required = false)
    void setSystemOverviewDocumentService(SystemOverviewDocumentService systemOverviewDocumentService) {
        this.systemOverviewDocumentService = systemOverviewDocumentService;
    }

    @Autowired(required = false)
    void setSystemOverviewIngestService(SystemOverviewIngestService systemOverviewIngestService) {
        this.systemOverviewIngestService = systemOverviewIngestService;
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
            }, job.getId());

            job.setStatus("SUCCESS");
            log.info("AI scan job completed: jobId={}", job.getId());
            
            // AI 任务完成后，刷新 ScanVersion 的统计数据（节点/边/事实数量）
            // 并更新版本的 finishedAt 和状态，确保总耗时包含 AI 编排时间
            try {
                io.github.legacygraph.entity.ScanVersion version = scanVersionRepository.selectById(job.getVersionId());
                if (version != null) {
                    projectScanner.applyStatsSnapshot(version, job.getProjectId(), job.getVersionId());
                    // 更新版本的完成时间和状态
                    version.setFinishedAt(LocalDateTime.now());
                    version.setScanStatus("SUCCESS");
                    scanVersionRepository.updateById(version);
                    log.info("ScanVersion updated after AI job: versionId={}, nodeCount={}, finishedAt={}", 
                            job.getVersionId(), version.getNodeCount(), version.getFinishedAt());
                    generateSystemOverviewDocument(job.getProjectId(), job.getVersionId());
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

    private void generateSystemOverviewDocument(String projectId, String versionId) {
        // 项目约定向量化 + 可复用组件标记（与 ProjectScanner 路径一致，覆盖 AI 完成路径）
        projectScanner.runPostScanConventionIngest(projectId, versionId);

        if (systemOverviewDocumentService == null) {
            log.debug("SystemOverviewDocumentService not available, skip AI completion markdown generation: versionId={}",
                    versionId);
            return;
        }
        // 先把当前项目图谱回溯成四层 Claim（BusinessDomain CONTAINS Feature / Feature IMPLEMENTED_BY
        // Controller / Service READS/WRITES Table 等）写入 lg_knowledge_claim，否则 generateAfterScan
        // 投影不到任何映射，报告只剩表头模板。ingest 失败不阻塞报告生成（降级到已有 Claim）。
        if (systemOverviewIngestService != null) {
            try {
                systemOverviewIngestService.ingestFromProjectGraph(projectId, versionId);
            } catch (Exception e) {
                log.warn("Failed to ingest system overview claims from graph before report generation: "
                        + "versionId={}, error={}", versionId, e.getMessage());
            }
        }
        try {
            systemOverviewDocumentService.generateAfterScan(projectId, versionId);
        } catch (Exception e) {
            log.warn("Failed to generate system overview markdown after AI completion: versionId={}, error={}",
                    versionId, e.getMessage());
        }
    }
}
