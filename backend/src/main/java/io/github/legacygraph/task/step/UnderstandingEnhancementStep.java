package io.github.legacygraph.task.step;

import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.ScanStep;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.understanding.ScanUnderstandingEnhancer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * AI_CODE_UNDERSTANDING — 扫描后代码理解增强，提取扫描结果中优先级最高的符号，
 * 调用 {@link ScanUnderstandingEnhancer} 进行深入分析。
 *
 * <p>增强失败不影响基础扫描状态。与 {@link KnowledgeGapStep} 同属 ENHANCE 状态机步骤。</p>
 */
@Slf4j
@Component
public class UnderstandingEnhancementStep implements AiScanStepExecutor {

    /** 关键符号上限，复用代码抽取节点上限常量语义 */
    private static final int MAX_CODE_EXTRACT_NODES = 30;

    private final AiScanStepSupport support;
    private final Neo4jGraphDao neo4jGraphDao;
    private final ScanUnderstandingEnhancer scanUnderstandingEnhancer;

    public UnderstandingEnhancementStep(AiScanStepSupport support,
                                        Neo4jGraphDao neo4jGraphDao,
                                        @Autowired(required = false) ScanUnderstandingEnhancer scanUnderstandingEnhancer) {
        this.support = support;
        this.neo4jGraphDao = neo4jGraphDao;
        this.scanUnderstandingEnhancer = scanUnderstandingEnhancer;
    }

    @Override
    public String getStepName() {
        return "AI_CODE_UNDERSTANDING";
    }

    @Override
    public int getOrder() {
        return 8;
    }

    @Override
    public ScanStep getScanStep() {
        return ScanStep.ENHANCE;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext ctx) {
        String projectId = ctx.getProjectId();
        String versionId = ctx.getVersionId();
        if (scanUnderstandingEnhancer == null) {
            log.debug("ScanUnderstandingEnhancer not available, skipping: versionId={}", versionId);
            return StepExecutionResult.builder().success(true)
                    .message("ScanUnderstandingEnhancer not available").build();
        }
        ScanTask task = support.createTask(projectId, versionId, "AI_CODE_UNDERSTANDING", "扫描后代码理解增强");
        try {
            // 提取 top symbols：按复杂度/入度排序的关键节点（Service/Controller/Method）
            List<String> topSymbols = extractTopSymbols(projectId, versionId);
            if (topSymbols.isEmpty()) {
                support.completeTask(task, "⚠ 无关键符号需要增强（缺少 Service/Controller/Method 等高价值节点）", null);
                return StepExecutionResult.builder().success(true)
                        .message("⚠ 无关键符号需要增强").build();
            }

            // 调用增强器
            ScanUnderstandingEnhancer.EnhancementResult result =
                    scanUnderstandingEnhancer.enhance(projectId, versionId, topSymbols);

            String summary = result.isEnabled()
                    ? String.format("增强 %d/%d 个符号成功（失败 %d），收集 %d 条证据：%s",
                            result.getEnhancedCount(), result.getEnhancedCount() + result.getFailCount(),
                            result.getFailCount(), result.getTotalEvidence(), result.getMessage())
                    : "增强未启用：" + result.getMessage();
            support.completeTask(task, summary, null);
            log.info("扫描后增强完成: projectId={}, versionId={}, result={}",
                    projectId, versionId, result.getMessage());
            return StepExecutionResult.builder().success(true).message(summary).build();
        } catch (Exception e) {
            // 增强失败不影响基础扫描状态
            log.error("扫描后代码理解增强失败: projectId={}, versionId={}, err={}",
                    projectId, versionId, e.getMessage());
            support.completeTask(task, null, "增强失败（不影响基础扫描）: " + e.getMessage());
            return StepExecutionResult.builder().success(false).message(e.getMessage()).build();
        }
    }

    /**
     * 提取扫描结果中优先级最高的符号（按复杂度/入度排序）。
     */
    private List<String> extractTopSymbols(String projectId, String versionId) {
        List<String> symbols = new ArrayList<>();

        // 查询 Service 节点
        List<GraphNode> serviceNodes = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Service.name(), null, null, null, MAX_CODE_EXTRACT_NODES);
        // 查询 Controller 节点
        List<GraphNode> controllerNodes = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Controller.name(), null, null, null, MAX_CODE_EXTRACT_NODES);
        // 查询 Method 节点
        List<GraphNode> methodNodes = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Method.name(), null, null, null, MAX_CODE_EXTRACT_NODES);

        // 合并并按优先级排序：Service > Controller > Method，同类取前几个
        List<GraphNode> allNodes = new ArrayList<>();
        allNodes.addAll(serviceNodes);
        allNodes.addAll(controllerNodes);
        allNodes.addAll(methodNodes);

        // 按 traceCount（被追踪次数，作为复杂度/入度的代理指标）降序排序
        allNodes.sort(Comparator.<GraphNode, Long>comparing(
                n -> n.getTraceCount() != null ? n.getTraceCount() : 0L).reversed());

        // 取前 MAX_CODE_EXTRACT_NODES 个
        int limit = Math.min(allNodes.size(), MAX_CODE_EXTRACT_NODES);
        for (int i = 0; i < limit; i++) {
            GraphNode node = allNodes.get(i);
            // 优先使用 nodeKey 作为符号标识，回退到 nodeName
            String symbol = node.getNodeKey() != null ? node.getNodeKey() : node.getNodeName();
            if (symbol != null && !symbol.isBlank()) {
                symbols.add(symbol);
            }
        }

        log.info("提取扫描后增强目标: projectId={}, versionId={}, count={}, types=[Service={}, Controller={}, Method={}]",
                projectId, versionId, symbols.size(),
                serviceNodes.size(), controllerNodes.size(), methodNodes.size());
        return symbols;
    }
}
