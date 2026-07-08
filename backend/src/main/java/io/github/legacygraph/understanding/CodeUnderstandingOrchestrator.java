package io.github.legacygraph.understanding;

import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.dto.understanding.CodeUnderstandingRequest;
import io.github.legacygraph.dto.understanding.CodeUnderstandingTaskResult;
import io.github.legacygraph.entity.ToolRunEntity;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.understanding.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import io.github.legacygraph.util.IdUtil;

/**
 * 代码理解编排器 —— 端到端编排问题拆解、工具执行、证据归档和报告生成。
 *
 * <p>流程：计划 → 路由 → 执行 → 归一化 → 记录 → 报告生成。
 *
 * <p>核心保障：
 * <ul>
 *   <li>工具不可用时自动降级到 LocalFallbackAdapter</li>
 *   <li>预算达到上限后停止继续调用</li>
 *   <li>任何外部工具失败不影响基础报告</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeUnderstandingOrchestrator {

    private final ToolQueryPlanner toolQueryPlanner;
    private final ToolRouter toolRouter;
    private final ToolRunRecorder toolRunRecorder;
    private final EvidenceNormalizer evidenceNormalizer;
    private final KnowledgeClaimService knowledgeClaimService;

    /**
     * 执行代码理解任务，生成报告。
     */
    public CodeUnderstandingTaskResult execute(String projectId, CodeUnderstandingRequest request) {
        String taskId = IdUtil.fastUUID();
        log.info("开始代码理解任务: taskId={}, projectId={}, question={}", taskId, projectId, request.getQuestion());

        // 构建工具策略
        ToolPolicy policy = buildPolicy(request);

        // 生成查询计划
        ToolQueryPlanner.CodeUnderstandingRequestWrapper wrapper = buildWrapper(request);
        ToolQueryPlanner.ToolQueryPlan plan = toolQueryPlanner.plan(wrapper);

        // 执行计划
        List<ToolResult> results = new ArrayList<>();
        List<KnowledgeClaimDraft> allClaims = new ArrayList<>();
        int totalEvidence = 0;
        int toolRuns = 0;

        for (ToolQueryPlanner.PlanStep step : plan.steps()) {
            if (toolRuns >= policy.getMaxToolRuns()) {
                log.info("达到工具调用上限: maxToolRuns={}", policy.getMaxToolRuns());
                break;
            }

            Optional<CodeUnderstandingToolAdapter> adapterOpt = toolRouter.route(step, policy);
            if (adapterOpt.isEmpty()) {
                log.warn("步骤无可用工具: {}", step.getDescription());
                continue;
            }

            CodeUnderstandingToolAdapter adapter = adapterOpt.get();
            toolRuns++;

            // 记录运行开始
            String queryHash = ToolRunRecorder.sha256(step.getDescription());
            ToolRunEntity runEntity = toolRunRecorder.recordRunStart(
                    projectId, request.getVersionId(),
                    adapter.toolName(), adapter.toolKind().name(),
                    step.getCapability().name(), queryHash);

            // 执行
            ToolRequest toolRequest = ToolRequest.builder()
                    .projectId(projectId)
                    .versionId(request.getVersionId())
                    .operation(step.getCapability())
                    .parameters(step.getParameters())
                    .build();

            ToolResult result;
            try {
                result = adapter.execute(toolRequest);
            } catch (Exception e) {
                log.error("工具执行异常: tool={}, error={}", adapter.toolName(), e.getMessage());
                result = ToolResult.builder()
                        .toolName(adapter.toolName())
                        .toolKind(adapter.toolKind())
                        .operation(step.getCapability())
                        .status("FAILED")
                        .errorExcerpt(e.getMessage())
                        .build();
            }

            // 记录运行完成
            toolRunRecorder.recordRunComplete(runEntity.getId(), result);
            results.add(result);

            // 归一化证据
            EvidenceNormalizer.NormalizationResult normalized = evidenceNormalizer.normalize(
                    result, projectId, request.getVersionId(), runEntity.getId());

            // 记录证据
            toolRunRecorder.recordEvidence(runEntity.getId(), normalized.evidenceRecords());
            totalEvidence += normalized.evidenceRecords().size();

            // 写入 Claim
            for (KnowledgeClaimDraft draft : normalized.claimDrafts()) {
                try {
                    knowledgeClaimService.upsertDraft(draft);
                    allClaims.add(draft);
                } catch (Exception e) {
                    log.error("Claim 写入失败: {}", e.getMessage());
                }
            }
        }

        // 统计待确认数量
        long pendingCount = allClaims.stream()
                .filter(this::isPendingConfirmClaim)
                .count();

        // 生成任务结果
        String reportId = "report-" + taskId;
        CodeUnderstandingTaskResult taskResult = CodeUnderstandingTaskResult.builder()
                .taskId(taskId)
                .status("SUCCESS")
                .reportId(reportId)
                .toolRuns(toolRuns)
                .evidenceCount(totalEvidence)
                .claimCount(allClaims.size())
                .pendingConfirmCount((int) pendingCount)
                .downloadUrl("/api/lg/projects/" + projectId + "/understanding/reports/" + taskId + "/download?format=MD")
                .build();

        log.info("代码理解任务完成: taskId={}, toolRuns={}, evidence={}, claims={}",
                taskId, toolRuns, totalEvidence, allClaims.size());
        return taskResult;
    }

    private boolean isPendingConfirmClaim(KnowledgeClaimDraft draft) {
        if (draft == null || draft.getSourceType() == null) {
            return true;
        }
        String sourceType = draft.getSourceType();
        boolean deterministicSource = List.of("CODE", "CODE_GRAPH", "DOC", "DB", "RUNTIME", "TEST")
                .contains(sourceType);
        if (!deterministicSource) {
            return true;
        }
        double confidence = draft.getConfidence() != null ? draft.getConfidence().doubleValue() : 0.5;
        return confidence < 0.85;
    }

    private ToolPolicy buildPolicy(CodeUnderstandingRequest request) {
        CodeUnderstandingRequest.ToolPolicyDto dto = request.getToolPolicy();
        if (dto == null) {
            return ToolPolicy.builder().build(); // 使用默认值
        }
        return ToolPolicy.builder()
                .enabledToolKinds(dto.getEnabledToolKinds() != null
                        ? dto.getEnabledToolKinds().stream().map(ToolKind::valueOf).toList()
                        : List.of(ToolKind.MCP, ToolKind.CLI, ToolKind.LOCAL))
                .allowedTools(dto.getAllowedTools())
                .executionMode(dto.getExecutionMode())
                .allowExternalNetwork(dto.isAllowExternalNetwork())
                .allowAiInference(dto.isAllowAiInference())
                .maxFilesToRead(dto.getMaxFilesToRead())
                .maxToolRuns(dto.getMaxToolRuns())
                .maxSeconds(dto.getMaxSeconds())
                .maxOutputBytes(dto.getMaxOutputBytes())
                .build();
    }

    private ToolQueryPlanner.CodeUnderstandingRequestWrapper buildWrapper(CodeUnderstandingRequest request) {
        return ToolQueryPlanner.CodeUnderstandingRequestWrapper.builder()
                .question(request.getQuestion())
                .scopePaths(request.getScope() != null ? request.getScope().getPaths() : null)
                .scopeSymbols(request.getScope() != null ? request.getScope().getSymbols() : null)
                .scopeFeatureKeys(request.getScope() != null ? request.getScope().getFeatureKeys() : null)
                .maxToolRuns(request.getToolPolicy() != null ? request.getToolPolicy().getMaxToolRuns() : 30)
                .build();
    }
}
