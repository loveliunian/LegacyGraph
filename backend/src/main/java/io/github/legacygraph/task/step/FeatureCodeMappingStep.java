package io.github.legacygraph.task.step;

import io.github.legacygraph.builder.BusinessGraphBuilder;
import io.github.legacygraph.common.ScanStep;
import io.github.legacygraph.entity.ScanTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI_FEATURE_CODE_MAPPING — 将文档/代码抽取的 Feature 节点按名称相似度映射到已有的
 * Page/API 实现，建立 EXPOSED_BY / IMPLEMENTED_BY 边，避免 Feature 成为孤立节点。
 *
 * <p>此前仅手动接口 {@code FactController.extractDocFacts} 会调用 mapFeaturesToCode，
 * 自动扫描路径遗漏该步骤，导致自动抽出的 Feature 与代码断连。此处对齐两条路径。</p>
 */
@Slf4j
@Component
public class FeatureCodeMappingStep implements AiScanStepExecutor {

    private final AiScanStepSupport support;
    private final BusinessGraphBuilder businessGraphBuilder;

    public FeatureCodeMappingStep(AiScanStepSupport support,
                                  BusinessGraphBuilder businessGraphBuilder) {
        this.support = support;
        this.businessGraphBuilder = businessGraphBuilder;
    }

    @Override
    public String getStepName() {
        return "AI_FEATURE_CODE_MAPPING";
    }

    @Override
    public int getOrder() {
        return 3;
    }

    @Override
    public ScanStep getScanStep() {
        return ScanStep.EXTRACT_FACTS;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext ctx) {
        String projectId = ctx.getProjectId();
        String versionId = ctx.getVersionId();
        ScanTask task = support.createTask(projectId, versionId, "AI_FEATURE_CODE_MAPPING", "功能到代码实现映射");
        try {
            int featureMappings = businessGraphBuilder.mapFeaturesToCode(projectId, versionId);
            // P2：业务对象 ↔ 数据库表对齐，连通业务层与技术层
            int objectMappings = businessGraphBuilder.mapBusinessObjectsToTables(projectId, versionId);
            // P2：业务域 ↔ Controller/Service 对齐，降低业务域 100% 孤立率
            int domainMappings = businessGraphBuilder.mapBusinessDomainsToCode(projectId, versionId);
            // P2：跨语言 Feature 去重合并（中文 DOC_AI ↔ 英文 FRONTEND_AST）
            int featureMerges = businessGraphBuilder.mergeCrossLanguageFeatures(projectId, versionId);
            int totalMappings = featureMappings + objectMappings + domainMappings;
            if (totalMappings == 0 && featureMerges == 0) {
                support.completeTask(task,
                        "⚠ 未建立 Feature/业务对象/业务域技术映射 —— 可能无 Feature/Page/API，"
                        + "或无 BusinessObject/BusinessDomain/技术实体候选",
                        null);
                return StepExecutionResult.builder().success(true)
                        .message("⚠ 未建立 Feature/业务对象/业务域技术映射").processedCount(0).build();
            } else {
                String summary = "已建立 Feature→Page/API 映射 " + featureMappings
                        + " 条，业务对象技术映射 " + objectMappings
                        + " 条，业务域技术映射 " + domainMappings
                        + " 条，跨语言 Feature 合并 " + featureMerges + " 组";
                support.completeTask(task, summary, null);
                return StepExecutionResult.builder().success(true).message(summary)
                        .processedCount(totalMappings).build();
            }
        } catch (Exception e) {
            log.error("AI_FEATURE_CODE_MAPPING failed: versionId={}", versionId, e);
            support.completeTask(task, null, e.getMessage());
            return StepExecutionResult.builder().success(false).message(e.getMessage()).build();
        }
    }
}
