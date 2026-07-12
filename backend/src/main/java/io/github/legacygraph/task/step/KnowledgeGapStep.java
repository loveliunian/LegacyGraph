package io.github.legacygraph.task.step;

import io.github.legacygraph.common.ScanStep;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.service.graph.GapFinderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AI_GAP_FINDING — 扫描结束后执行知识缺口发现（确定性 + LLM 增强）。
 *
 * <p>与 {@link UnderstandingEnhancementStep} 同属 ENHANCE 状态机步骤。</p>
 */
@Slf4j
@Component
public class KnowledgeGapStep implements AiScanStepExecutor {

    private final AiScanStepSupport support;
    private final GapFinderService gapFinderService;

    public KnowledgeGapStep(AiScanStepSupport support,
                            @Autowired(required = false) GapFinderService gapFinderService) {
        this.support = support;
        this.gapFinderService = gapFinderService;
    }

    @Override
    public String getStepName() {
        return "AI_GAP_FINDING";
    }

    @Override
    public int getOrder() {
        return 7;
    }

    @Override
    public ScanStep getScanStep() {
        return ScanStep.GAP_FINDING;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext ctx) {
        String projectId = ctx.getProjectId();
        String versionId = ctx.getVersionId();
        if (gapFinderService == null) {
            log.debug("GapFinderService not available, skipping gap scan: versionId={}", versionId);
            return StepExecutionResult.builder().success(true)
                    .message("GapFinderService not available").build();
        }
        ScanTask task = support.createTask(projectId, versionId, "AI_GAP_FINDING", "知识缺口扫描");
        try {
            GapFinderService.GapScanResult result = gapFinderService.scanGaps(projectId, versionId);
            String summary = "生成知识缺口 " + result.getCreated()
                    + " 条，重新打开 " + result.getReopened()
                    + " 条，保持 " + result.getUnchanged() + " 条";
            support.completeTask(task, summary, null);
            return StepExecutionResult.builder().success(true).message(summary).build();
        } catch (Exception e) {
            log.error("AI_GAP_FINDING failed: versionId={}", versionId, e);
            support.completeTask(task, null, e.getMessage());
            return StepExecutionResult.builder().success(false).message(e.getMessage()).build();
        }
    }
}
