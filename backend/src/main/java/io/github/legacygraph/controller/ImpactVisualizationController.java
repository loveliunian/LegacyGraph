package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.requirement.ImpactNode;
import io.github.legacygraph.dto.requirement.ImpactNodeRisk;
import io.github.legacygraph.dto.requirement.ImpactPath;
import io.github.legacygraph.dto.requirement.ImpactResult;
import io.github.legacygraph.dto.requirement.ImpactVisualizationData;
import io.github.legacygraph.dto.requirement.LinkedTarget;
import io.github.legacygraph.entity.Requirement;
import io.github.legacygraph.entity.RequirementItem;
import io.github.legacygraph.repository.RequirementItemRepository;
import io.github.legacygraph.repository.RequirementRepository;
import io.github.legacygraph.service.requirement.ImpactSubgraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 需求影响可视化 Controller（G-20）。
 * <p>从已保存的需求条目出发重建链接目标，调用 {@link ImpactSubgraphService}
 * 提取影响子图，并转换为前端可视化组件直接消费的 {@link ImpactVisualizationData}。</p>
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/impact-viz")
@Tag(name = "需求影响可视化", description = "需求影响子图可视化数据")
public class ImpactVisualizationController {

    /** 高风险阈值：riskScore >= 此值视为高风险节点 */
    private static final double HIGH_RISK_THRESHOLD = 0.7;

    private final ImpactSubgraphService impactService;
    private final RequirementRepository requirementRepository;
    private final RequirementItemRepository itemRepository;

    public ImpactVisualizationController(ImpactSubgraphService impactService,
                                        RequirementRepository requirementRepository,
                                        RequirementItemRepository itemRepository) {
        this.impactService = impactService;
        this.requirementRepository = requirementRepository;
        this.itemRepository = itemRepository;
    }

    /**
     * 获取需求影响可视化数据。
     * <p>从需求条目构建链接目标（nodeKey 格式 {@code req:{requirementId}:{code}}，
     * 与 RequirementGraphBuilder 创建的图谱节点一致），提取影响子图后转换为可视化 DTO。</p>
     *
     * @param projectId     项目 ID
     * @param requirementId 需求 ID
     * @return 影响可视化数据（节点 + 边 + 摘要）
     */
    @GetMapping("/requirements/{requirementId}")
    @Operation(summary = "获取需求影响可视化数据",
            description = "从需求条目出发提取影响子图，返回节点、边与摘要统计，供前端影响子图可视化组件渲染")
    public Result<ImpactVisualizationData> getVisualization(
            @PathVariable String projectId,
            @PathVariable String requirementId) {
        Requirement req = requirementRepository.selectById(requirementId);
        if (req == null) {
            throw new IllegalArgumentException("需求不存在: " + requirementId);
        }
        List<LinkedTarget> targets = buildTargetsFromItems(requirementId);
        // versionId 传 null：extract 内部 findPathsDirected 会以全图遍历
        ImpactResult result = impactService.extract(projectId, null, requirementId, targets);
        ImpactVisualizationData data = toVisualizationData(result);
        log.info("Impact visualization: projectId={}, requirementId={}, nodes={}, edges={}",
                projectId, requirementId, data.getNodes().size(), data.getEdges().size());
        return Result.success(data);
    }

    /**
     * 获取影响摘要（简化统计数据）。
     *
     * @param projectId     项目 ID
     * @param requirementId 需求 ID
     * @return 影响摘要统计
     */
    @GetMapping("/requirements/{requirementId}/summary")
    @Operation(summary = "获取需求影响摘要",
            description = "返回影响子图的简化统计：总节点数、各级别数量、最大风险分数、高风险节点列表")
    public Result<ImpactVisualizationData.VizSummary> getSummary(
            @PathVariable String projectId,
            @PathVariable String requirementId) {
        Requirement req = requirementRepository.selectById(requirementId);
        if (req == null) {
            throw new IllegalArgumentException("需求不存在: " + requirementId);
        }
        List<LinkedTarget> targets = buildTargetsFromItems(requirementId);
        ImpactResult result = impactService.extract(projectId, null, requirementId, targets);
        ImpactVisualizationData.VizSummary summary = buildSummary(result);
        log.info("Impact summary: projectId={}, requirementId={}, totalNodes={}, maxRisk={}",
                projectId, requirementId, summary.getTotalNodes(), summary.getMaxRiskScore());
        return Result.success(summary);
    }

    // ==================== 内部转换逻辑 ====================

    /**
     * 从需求条目构建链接目标列表。
     * <p>nodeKey 采用 {@code req:{requirementId}:{code}} 格式，
     * 与 {@code RequirementGraphBuilder} 创建的 RequirementItem 图谱节点 key 一致。</p>
     */
    private List<LinkedTarget> buildTargetsFromItems(String requirementId) {
        LambdaQueryWrapper<RequirementItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementItem::getRequirementId, requirementId);
        List<RequirementItem> items = itemRepository.selectList(wrapper);
        List<LinkedTarget> targets = new ArrayList<>();
        for (RequirementItem item : items) {
            String code = item.getCode() != null ? item.getCode() : "R1";
            LinkedTarget target = new LinkedTarget();
            target.setNodeId(item.getId());
            target.setNodeKey("req:" + requirementId + ":" + code);
            target.setNodeName(item.getText());
            target.setNodeType("RequirementItem");
            target.setMatchType("DIRECT");
            target.setConfidence(BigDecimal.ONE);
            target.setStatus("CONFIRMED");
            target.setItemCode(code);
            target.setItemText(item.getText());
            targets.add(target);
        }
        return targets;
    }

    /**
     * 将 ImpactResult 转换为可视化数据：合并节点基础信息与风险评估、从路径派生边、构建摘要。
     */
    private ImpactVisualizationData toVisualizationData(ImpactResult result) {
        // nodeId → ImpactNodeRisk 映射，便于合并
        Map<String, ImpactNodeRisk> riskMap = new HashMap<>();
        if (result.getNodeRisks() != null) {
            for (ImpactNodeRisk risk : result.getNodeRisks()) {
                riskMap.put(risk.getNodeId(), risk);
            }
        }

        // 构建可视化节点：合并 impactedNodes 与 nodeRisks
        List<ImpactVisualizationData.VizNode> vizNodes = new ArrayList<>();
        if (result.getImpactedNodes() != null) {
            for (ImpactNode node : result.getImpactedNodes()) {
                ImpactNodeRisk risk = riskMap.get(node.getNodeId());
                ImpactVisualizationData.VizNode vn = ImpactVisualizationData.VizNode.builder()
                        .id(node.getNodeId())
                        .label(node.getNodeName())
                        .type(node.getNodeType())
                        .impactLevel(risk != null ? risk.getImpactLevel() : "L4")
                        .riskScore(risk != null ? risk.getRiskScore() : 0.0)
                        .depth(node.getDepth())
                        .build();
                vizNodes.add(vn);
            }
        }

        // 从影响路径派生边：相邻节点对构成一条边（按 nodeKey 去重）
        List<ImpactVisualizationData.VizEdge> vizEdges = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();
        if (result.getPaths() != null) {
            for (ImpactPath path : result.getPaths()) {
                List<String> pathNodes = path.getPathNodes();
                if (pathNodes == null || pathNodes.size() < 2) {
                    continue;
                }
                for (int i = 0; i < pathNodes.size() - 1; i++) {
                    String src = pathNodes.get(i);
                    String dst = pathNodes.get(i + 1);
                    if (src == null || dst == null) {
                        continue;
                    }
                    String edgeKey = src + "->" + dst;
                    if (edgeKeys.add(edgeKey)) {
                        vizEdges.add(ImpactVisualizationData.VizEdge.builder()
                                .source(src)
                                .target(dst)
                                .relationType(path.getImpactType())
                                .build());
                    }
                }
            }
        }

        ImpactVisualizationData.VizSummary summary = buildSummary(result);
        return ImpactVisualizationData.builder()
                .nodes(vizNodes)
                .edges(vizEdges)
                .summary(summary)
                .build();
    }

    /**
     * 从 ImpactResult 构建摘要统计：总节点数、各级别数量、最大风险分数、高风险节点清单。
     */
    private ImpactVisualizationData.VizSummary buildSummary(ImpactResult result) {
        List<ImpactNodeRisk> risks = result.getNodeRisks();
        if (risks == null || risks.isEmpty()) {
            return ImpactVisualizationData.VizSummary.builder()
                    .totalNodes(result.getImpactedNodes() != null ? result.getImpactedNodes().size() : 0)
                    .byLevel(new LinkedHashMap<>())
                    .maxRiskScore(0.0)
                    .highRiskNodes(List.of())
                    .build();
        }

        // 按影响层级分组计数
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        double maxRisk = 0.0;
        List<String> highRiskNodes = new ArrayList<>();
        for (ImpactNodeRisk risk : risks) {
            String level = risk.getImpactLevel() != null ? risk.getImpactLevel() : "L4";
            byLevel.merge(level, 1, Integer::sum);
            if (risk.getRiskScore() > maxRisk) {
                maxRisk = risk.getRiskScore();
            }
            if (risk.getRiskScore() >= HIGH_RISK_THRESHOLD) {
                String name = risk.getNodeName() != null ? risk.getNodeName() : risk.getNodeId();
                highRiskNodes.add(name);
            }
        }
        return ImpactVisualizationData.VizSummary.builder()
                .totalNodes(risks.size())
                .byLevel(byLevel)
                .maxRiskScore(maxRisk)
                .highRiskNodes(highRiskNodes)
                .build();
    }
}
