package io.github.legacygraph.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.builder.PgEvidenceTxExecutor;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.entity.AiScanJob;
import io.github.legacygraph.repository.AiScanJobRepository;
import io.github.legacygraph.service.scan.ScanArtifactPublisher;
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
    private PgEvidenceTxExecutor pgEvidenceTxExecutor;
    private SystemOverviewDocumentService systemOverviewDocumentService;
    private SystemOverviewIngestService systemOverviewIngestService;
    private ScanArtifactPublisher scanArtifactPublisher;
    private io.github.legacygraph.service.scan.ScanFinalizationService scanFinalizationService;
    private io.github.legacygraph.config.GraphReleaseConfig graphReleaseConfig;
    private io.github.legacygraph.service.graph.GraphQueryService graphQueryService;

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
    void setPgEvidenceTxExecutor(PgEvidenceTxExecutor pgEvidenceTxExecutor) {
        this.pgEvidenceTxExecutor = pgEvidenceTxExecutor;
    }

    @Autowired(required = false)
    void setSystemOverviewDocumentService(SystemOverviewDocumentService systemOverviewDocumentService) {
        this.systemOverviewDocumentService = systemOverviewDocumentService;
    }

    @Autowired(required = false)
    void setSystemOverviewIngestService(SystemOverviewIngestService systemOverviewIngestService) {
        this.systemOverviewIngestService = systemOverviewIngestService;
    }

    @Autowired(required = false)
    void setScanArtifactPublisher(ScanArtifactPublisher scanArtifactPublisher) {
        this.scanArtifactPublisher = scanArtifactPublisher;
    }

    @Autowired(required = false)
    void setScanFinalizationService(io.github.legacygraph.service.scan.ScanFinalizationService scanFinalizationService) {
        this.scanFinalizationService = scanFinalizationService;
    }

    @Autowired(required = false)
    void setGraphReleaseConfig(io.github.legacygraph.config.GraphReleaseConfig graphReleaseConfig) {
        this.graphReleaseConfig = graphReleaseConfig;
    }

    @Autowired(required = false)
    void setGraphQueryService(io.github.legacygraph.service.graph.GraphQueryService graphQueryService) {
        this.graphQueryService = graphQueryService;
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
            
            // AI 任务完成后，flush 剩余证据
            if (pgEvidenceTxExecutor != null) {
                try {
                    pgEvidenceTxExecutor.flush();
                    log.info("Flushed remaining evidence after AI job: jobId={}", job.getId());
                } catch (Exception ex) {
                    log.warn("Failed to flush evidence after AI job: {}", ex.getMessage());
                }
            }
            
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
                    // S3-T5: 扫描完成后清除图谱缓存，确保下次查询获取最新数据
                    if (graphQueryService != null) {
                        graphQueryService.evictGraphCache(job.getVersionId());
                        log.debug("Graph cache evicted after AI job completion: versionId={}", job.getVersionId());
                    }
                    if (isScanFinalizationEnabled()) {
                        runScanFinalization(job.getProjectId(), job.getVersionId());
                    } else {
                        generateSystemOverviewDocument(job.getProjectId(), job.getVersionId());
                        publishScanArtifacts(job.getProjectId(), job.getVersionId());
                    }
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

    /**
     * AI 编排完成后发布扫描产物（边补全 / 社区检测 / 质量报告 / 总结文档发布到 docs/legacygraph 并向量化）。
     *
     * <p>在 {@link #generateSystemOverviewDocument} 之后调用，确保总结基于完整的 AI 增强图谱。
     * 失败只 warn，不阻塞 AI 扫描主流程。</p>
     */
    private void publishScanArtifacts(String projectId, String versionId) {
        if (scanArtifactPublisher == null) {
            log.debug("ScanArtifactPublisher not available, skip publishing scan artifacts after AI: versionId={}",
                    versionId);
            return;
        }
        try {
            scanArtifactPublisher.publish(projectId, versionId);
        } catch (Exception e) {
            log.warn("ScanArtifactPublisher failed after AI (non-blocking): versionId={}, error={}",
                    versionId, e.getMessage());
        }
    }

    /**
     * 判断是否启用 ScanFinalizationService 收口路径。
     * <p>需同时满足：{@code legacygraph.graph-release.enabled=true} 且 {@link ScanFinalizationService} 已注入。</p>
     */
    private boolean isScanFinalizationEnabled() {
        return graphReleaseConfig != null && graphReleaseConfig.isEnabled()
                && scanFinalizationService != null;
    }

    /**
     * 调用 ScanFinalizationService 统一收口 AI 扫描流程。
     * <p>替代旧路径的 {@code generateSystemOverviewDocument} + {@code publishScanArtifacts}，
     * 由 {@link ScanFinalizationService#finalize} 统一编排收口流程。失败只 warn，不阻塞 AI 扫描主流程。</p>
     */
    private void runScanFinalization(String projectId, String versionId) {
        try {
            scanFinalizationService.finalize(projectId, versionId);
        } catch (Exception e) {
            log.warn("ScanFinalizationService failed after AI (non-blocking): versionId={}, error={}",
                    versionId, e.getMessage(), e);
        }
    }
}
