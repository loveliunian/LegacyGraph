package io.github.legacygraph.service.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.qa.QaEvaluationResult;
import io.github.legacygraph.dto.scan.Decision;
import io.github.legacygraph.entity.GraphRelease;
import io.github.legacygraph.eval.GraphifyQualityResult;
import io.github.legacygraph.eval.GraphifyQualityService;
import io.github.legacygraph.service.graph.GraphReleaseService;
import io.github.legacygraph.service.qa.QaEvaluationService;
import io.github.legacygraph.service.qa.SemanticCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扫描收口服务 — 扫描完成后统一编排收口流程，替代旧路径中分散的
 * {@code runPostScanConventionIngest} + {@code publishScanArtifacts} 调用。
 *
 * <p>仅当 {@code legacygraph.graph-release.enabled=true} 时由
 * {@code ProjectScanner} / {@code AiScanJobWorker} 调用；关闭时保留旧路径。</p>
 *
 * <p>编排顺序（每步独立 try/catch，单步失败不阻塞后续）：
 * <ol>
 *   <li>约定提取 — {@link ProjectConventionIngestService#ingest}</li>
 *   <li>可复用标记 — {@link ReusableComponentMarker#mark}</li>
 *   <li>质量评估 — {@link GraphQualityAssessor#assessAndReport}，生成 graph-quality-report.md</li>
 *   <li>边补全 — {@link EdgeCompletionService#completeAll}（传递闭包 + 规则校验）</li>
 *   <li>社区检测 — {@link CommunityDetectionService#detectCommunities} + {@link CommunityDetectionService#writeCommunityToNodes}</li>
 *   <li>社区摘要 — {@link CommunityDetectionService#generateCommunitySummaries}（Feature Flag：legacygraph.community.summary.enabled，默认关闭）</li>
 *   <li>产物发布 — {@link ScanArtifactPublisher#publishArtifactsOnly}（总结文档 + 向量化，不含图谱定稿步骤）</li>
 *   <li>质量门禁 — {@link GraphQualityGate#evaluate}，返回 {@link Decision}</li>
 *   <li>QA 评测门禁 — 仅当质量门禁通过时执行 {@link QaEvaluationService#runSmoke}，
 *       entityRecall&lt;0.85 / evidencePrecision&lt;0.90 / abstentionAccuracy&lt;0.95 任一不满足即阻止发布</li>
 *   <li>GraphRelease 发布 — {@link GraphReleaseService#startValidation}，
 *       门禁通过走 {@link GraphReleaseService#markPublished}，
 *       不通过走 {@link GraphReleaseService#markFailed} 并记录失败原因</li>
 *   <li>缓存失效 — 发布成功后 {@link SemanticCache#invalidateByProject}</li>
 * </ol>
 *
 * <p>设计约束参考 {@link ScanArtifactPublisher}：失败只 warn，不阻塞扫描主流程。
 * 但 GraphRelease 发布与缓存失效是收口的最后关键步骤，若门禁评估本身异常导致无法决策，
 * 仍会尝试标记发布失败，避免发布记录卡在 VALIDATING。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanFinalizationService {

    private final ProjectConventionIngestService projectConventionIngestService;
    private final ReusableComponentMarker reusableComponentMarker;
    private final GraphQualityAssessor graphQualityAssessor;
    private final EdgeCompletionService edgeCompletionService;
    private final CommunityDetectionService communityDetectionService;
    private final ScanArtifactPublisher scanArtifactPublisher;
    private final GraphQualityGate graphQualityGate;
    private final GraphReleaseService graphReleaseService;
    private final SemanticCache semanticCache;
    private final QaEvaluationService qaEvaluationService;
    private final ObjectMapper objectMapper;

    /**
     * Graphify 质量评估服务，可选注入（graphify 可能禁用或 Bean 未注册）。
     * 用于在发布时计算 GraphifyQualityResult 并写入 GraphRelease.metrics。
     */
    @Autowired(required = false)
    private GraphifyQualityService graphifyQualityService;

    /** Feature Flag：社区摘要生成开关，默认关闭 */
    @Value("${legacygraph.community.summary.enabled:false}")
    private boolean communitySummaryEnabled;

    /**
     * 执行扫描收口流程。
     *
     * <p>各步骤独立 try/catch，单步失败不阻塞后续步骤。
     * GraphRelease 发布结果由质量门禁决策决定：门禁通过 → PUBLISHED + 缓存失效；
     * 门禁不通过 → FAILED + 记录失败原因。</p>
     *
     * @param projectId     项目 ID
     * @param scanVersionId 扫描版本 ID
     */
    public void finalize(String projectId, String scanVersionId) {
        log.info("ScanFinalizationService: starting finalization for projectId={}, scanVersionId={}",
                projectId, scanVersionId);

        // 1. 约定提取（技术栈 + 分层规范 + 命名约定 → 向量化）
        runStep("convention-ingest", () -> {
            projectConventionIngestService.ingest(projectId, scanVersionId);
        });

        // 2. 可复用组件标记（EXTENDS 入度统计 → reusable=true）
        runStep("reusable-marker", () -> {
            int marked = reusableComponentMarker.mark(projectId, scanVersionId);
            log.info("ScanFinalizationService: reusable component marking done, marked={}", marked);
        });

        // 3. 质量评估（生成 graph-quality-report.md，在图谱定稿前先产出基线报告）
        runStep("quality-assess", () -> {
            graphQualityAssessor.assessAndReport(projectId, scanVersionId);
        });

        // 4. 边补全（传递闭包 + 规则校验，提高图谱连通性）
        runStep("edge-completion", () -> {
            EdgeCompletionService.CompletionReport report = edgeCompletionService.completeAll(projectId, scanVersionId);
            log.info("ScanFinalizationService: edge completion done, transitiveEdges={}, belongsToFixed={}, anomalies={}",
                    report.getTransitiveEdgesAdded(), report.getBelongsToFixed(), report.getAnomalies().size());
        });

        // 5. 社区检测（标签传播 → Package 节点 properties.community）
        runStep("community-detection", () -> {
            Map<String, String> communityMap = communityDetectionService.detectCommunities(projectId);
            if (communityMap != null && !communityMap.isEmpty()) {
                communityDetectionService.writeCommunityToNodes(projectId, communityMap);
                Set<String> communityLabels = Set.copyOf(communityMap.values());
                log.info("ScanFinalizationService: community detection done, communities={}", communityLabels.size());
            }
        });

        // 5.1 社区摘要生成（Feature Flag 控制，默认关闭）
        if (communitySummaryEnabled) {
            runStep("community-summary", () -> {
                communityDetectionService.generateCommunitySummaries(projectId);
            });
        }

        // 6. 产物发布（总结文档 → docs/legacygraph + 向量化，不含图谱定稿步骤）
        runStep("artifact-publish", () -> {
            scanArtifactPublisher.publishArtifactsOnly(projectId, scanVersionId);
        });

        // 7. 质量门禁评估
        Decision decision;
        try {
            decision = graphQualityGate.evaluate(projectId, scanVersionId);
            log.info("ScanFinalizationService: quality gate evaluated, passed={}, reasons={}",
                    decision.passed(), decision.reasons());
        } catch (Exception e) {
            // 门禁评估本身异常：无法决策是否放行，按失败处理并记录异常原因
            log.error("ScanFinalizationService: quality gate evaluation failed, marking release as FAILED: {}",
                    e.getMessage(), e);
            handleReleaseFailure(projectId, scanVersionId,
                    List.of("QUALITY_GATE_EVALUATION_ERROR: " + e.getMessage()));
            return;
        }

        // 8. QA 评测门禁（仅当质量门禁通过时执行；评测不通过阻止发布）
        if (decision.passed()) {
            QaEvaluationResult qaResult;
            try {
                qaResult = qaEvaluationService.runSmoke(projectId, scanVersionId);
            } catch (Exception e) {
                log.error("ScanFinalizationService: QA smoke evaluation failed, blocking release: projectId={}, scanVersionId={}, error={}",
                        projectId, scanVersionId, e.getMessage(), e);
                List<String> reasons = new ArrayList<>();
                reasons.add("QA_EVALUATION_ERROR: " + e.getMessage());
                handleReleaseFailure(projectId, scanVersionId, reasons);
                return;
            }
            if (!qaResult.isPassed()) {
                List<String> reasons = new ArrayList<>();
                reasons.add("QA_GATE_FAILED: entityRecall=" + qaResult.getEntityRecall()
                        + ", evidencePrecision=" + qaResult.getEvidencePrecision()
                        + ", abstentionAccuracy=" + qaResult.getAbstentionAccuracy());
                reasons.addAll(qaResult.getFailureReasons());
                log.warn("ScanFinalizationService: QA gate failed, blocking release: projectId={}, scanVersionId={}, reasons={}",
                        projectId, scanVersionId, reasons);
                handleReleaseFailure(projectId, scanVersionId, reasons);
                return;
            }
            log.info("ScanFinalizationService: QA gate passed: entityRecall={}, evidencePrecision={}, abstentionAccuracy={}",
                    qaResult.getEntityRecall(), qaResult.getEvidencePrecision(), qaResult.getAbstentionAccuracy());
        }

        // 9. GraphRelease 发布（startValidation → markPublished / markFailed）
        if (decision.passed()) {
            handleReleaseSuccess(projectId, scanVersionId);
        } else {
            handleReleaseFailure(projectId, scanVersionId, decision.reasons());
        }
    }

    /**
     * 门禁通过：startValidation → markPublished → 缓存失效。
     * 各子步骤失败时记录日志但不抛异常，避免影响扫描主流程。
     */
    private void handleReleaseSuccess(String projectId, String scanVersionId) {
        GraphRelease release;
        try {
            release = graphReleaseService.startValidation(projectId, scanVersionId);
        } catch (Exception e) {
            log.warn("ScanFinalizationService: GraphRelease startValidation failed (non-blocking): projectId={}, scanVersionId={}, error={}",
                    projectId, scanVersionId, e.getMessage());
            return;
        }

        // 已是终态（PUBLISHED / FAILED）的幂等返回不重复 markPublished
        if (!"VALIDATING".equals(release.getStatus())) {
            log.info("ScanFinalizationService: GraphRelease already in terminal status {}, skip markPublished: id={}",
                    release.getStatus(), release.getId());
            // 即使是已发布的幂等场景，也失效缓存（确保新版本数据生效）
            invalidateCache(projectId);
            return;
        }

        // 计算并序列化 Graphify 质量指标（失败不阻断发布）
        String metricsJson = resolveGraphifyMetrics(projectId, scanVersionId);

        try {
            graphReleaseService.markPublished(release.getId(), metricsJson);
            log.info("ScanFinalizationService: GraphRelease published: id={}, projectId={}, scanVersionId={}, metrics={}",
                    release.getId(), projectId, scanVersionId, metricsJson != null ? "written" : "skipped");
            // 9. 缓存失效（发布成功后才失效，避免门禁失败时清掉仍可能被使用的缓存）
            invalidateCache(projectId);
        } catch (Exception e) {
            log.warn("ScanFinalizationService: GraphRelease markPublished failed (non-blocking): id={}, error={}",
                    release.getId(), e.getMessage());
        }
    }

    /**
     * 计算 Graphify 质量指标并序列化为 JSON。
     * GraphifyQualityService 未注入或评估异常时返回 null，不阻断发布。
     */
    private String resolveGraphifyMetrics(String projectId, String scanVersionId) {
        if (graphifyQualityService == null) {
            return null;
        }
        try {
            GraphifyQualityResult result = graphifyQualityService.getQuality(projectId, scanVersionId);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("ScanFinalizationService: graphify quality metrics resolution failed (non-blocking): projectId={}, scanVersionId={}, error={}",
                    projectId, scanVersionId, e.getMessage());
            return null;
        }
    }

    /**
     * 门禁不通过或评估异常：startValidation → markFailed + 记录失败原因。
     * 不失效缓存（门禁失败意味着图谱未达到发布标准，旧缓存仍可服务）。
     */
    private void handleReleaseFailure(String projectId, String scanVersionId, List<String> reasons) {
        GraphRelease release;
        try {
            release = graphReleaseService.startValidation(projectId, scanVersionId);
        } catch (Exception e) {
            log.warn("ScanFinalizationService: GraphRelease startValidation failed during failure handling (non-blocking): projectId={}, scanVersionId={}, error={}",
                    projectId, scanVersionId, e.getMessage());
            return;
        }

        // 已是终态的幂等返回不重复 markFailed
        if (!"VALIDATING".equals(release.getStatus())) {
            log.info("ScanFinalizationService: GraphRelease already in terminal status {}, skip markFailed: id={}",
                    release.getStatus(), release.getId());
            return;
        }

        try {
            List<String> safeReasons = reasons != null ? reasons : new ArrayList<>();
            graphReleaseService.markFailed(release.getId(), safeReasons);
            log.info("ScanFinalizationService: GraphRelease marked FAILED: id={}, projectId={}, scanVersionId={}, reasons={}",
                    release.getId(), projectId, scanVersionId, safeReasons);
        } catch (Exception e) {
            log.warn("ScanFinalizationService: GraphRelease markFailed failed (non-blocking): id={}, error={}",
                    release.getId(), e.getMessage());
        }
    }

    /**
     * 失效项目级语义缓存，失败只 warn 不阻塞。
     */
    private void invalidateCache(String projectId) {
        try {
            semanticCache.invalidateByProject(projectId);
        } catch (Exception e) {
            log.warn("ScanFinalizationService: semantic cache invalidation failed (non-blocking): projectId={}, error={}",
                    projectId, e.getMessage());
        }
    }

    /**
     * 执行单个收口步骤，统一捕获异常并记录日志，不向上传播。
     *
     * @param stepName 步骤名（用于日志标识）
     * @param step     步骤逻辑
     */
    private void runStep(String stepName, Runnable step) {
        try {
            step.run();
        } catch (Exception e) {
            log.warn("ScanFinalizationService: step '{}' failed (non-blocking): {}", stepName, e.getMessage(), e);
        }
    }
}
