package io.github.legacygraph.service.solution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.dto.requirement.ImpactNode;
import io.github.legacygraph.dto.requirement.ImpactResult;
import io.github.legacygraph.dto.solution.SolutionPlan;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.llm.LlmGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 方案规划服务（Task 10）。
 * <p>基于 {@link RequirementAnalysis}（需求分析）+ {@link ImpactResult}（影响子图）
 * + 项目约定 + 可复用组件，调用 LLM 生成文件级实施步骤，输出 {@link SolutionPlan}。</p>
 *
 * <p>调用 {@link LlmGateway#callWithTemplate} 走 solution-planning prompt 模板，
 * 模板变量：{requirementAnalysis}, {impactResult}, {conventions}, {reusableComponents}。</p>
 *
 * <p>项目约定从图谱节点统计推导（标准分层 Controller/Service/Mapper/Entity 的样本类名）；
 * 可复用组件从图谱中 properties.reusable=true 的节点加载。</p>
 */
@Slf4j
@Service
public class SolutionPlanner {

    /** prompt 模板名，对应 classpath:/prompts/solution-planning.txt */
    static final String TEMPLATE_NAME = "solution-planning";

    /** 项目约定与可复用组件查询的节点数上限 */
    private static final int CONVENTION_NODE_LIMIT = 50;
    private static final int REUSABLE_NODE_LIMIT = 100;

    /** 影响签名提取的节点数上限（避免 prompt 过长） */
    private static final int IMPACT_SIGNATURE_NODE_LIMIT = 20;

    private final LlmGateway llmGateway;
    private final Neo4jGraphDao neo4jGraphDao;
    private final ObjectMapper objectMapper;

    public SolutionPlanner(LlmGateway llmGateway,
                           Neo4jGraphDao neo4jGraphDao,
                           ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.neo4jGraphDao = neo4jGraphDao;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成方案计划。
     *
     * @param projectId           项目 ID
     * @param versionId           扫描版本 ID（可为 null，查询全版本）
     * @param requirementAnalysis 需求分析结果
     * @param impactResult        影响子图结果
     * @return LLM 输出的方案计划（含 summary + steps）
     */
    public SolutionPlan plan(String projectId, String versionId,
                              RequirementAnalysis requirementAnalysis,
                              ImpactResult impactResult) {
        if (requirementAnalysis == null) {
            throw new IllegalArgumentException("需求分析不能为空");
        }

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("requirementAnalysis", writeJsonSafe(requirementAnalysis));
        variables.put("impactResult", writeJsonSafe(impactResult != null ? impactResult : new ImpactResult()));
        variables.put("impactSignatures", loadImpactSignatures(impactResult));
        variables.put("conventions", loadConventions(projectId, versionId));
        variables.put("reusableComponents", loadReusableComponents(projectId, versionId));

        log.info("Solution planning started: projectId={}, items={}, impactedNodes={}",
                projectId,
                requirementAnalysis.getItems() != null ? requirementAnalysis.getItems().size() : 0,
                impactResult != null ? impactResult.getImpactedNodes().size() : 0);

        SolutionPlan plan = llmGateway.callWithTemplate(
                projectId, TEMPLATE_NAME, variables, SolutionPlan.class);

        if (plan == null) {
            plan = new SolutionPlan();
        }
        if (plan.getSteps() == null) {
            plan.setSteps(new ArrayList<>());
        }
        // 防御性兜底：每个 step 的 evidenceIds 不能为 null
        plan.getSteps().forEach(step -> {
            if (step.getEvidenceIds() == null) {
                step.setEvidenceIds(new ArrayList<>());
            }
        });

        log.info("Solution planning completed: projectId={}, steps={}",
                projectId, plan.getSteps().size());
        return plan;
    }

    // ==================== 项目约定加载 ====================

    /**
     * 从图谱节点统计推导项目约定（分层规范 + 命名约定样本）。
     * <p>查询 Controller / Service / Mapper / Entity 类型节点，取前 N 个样本类名，
     * 组装为简洁文本供 LLM 参考。失败时返回空字符串。</p>
     */
    String loadConventions(String projectId, String versionId) {
        try {
            Map<String, Object> conventions = new LinkedHashMap<>();
            for (String layer : List.of("Controller", "Service", "Mapper")) {
                List<GraphNode> nodes = neo4jGraphDao.queryNodes(
                        projectId, versionId, layer, null, null,
                        null, null, CONVENTION_NODE_LIMIT);
                List<String> sampleNames = new ArrayList<>();
                for (GraphNode n : nodes) {
                    if (n.getNodeName() != null && !n.getNodeName().isBlank()) {
                        sampleNames.add(n.getNodeName());
                    }
                }
                conventions.put(layer.toLowerCase() + "Samples", sampleNames);
                conventions.put(layer.toLowerCase() + "Count", nodes.size());
            }
            // Entity 类的样本（以辅助 LLM 推断命名约定）
            List<GraphNode> entities = neo4jGraphDao.queryNodes(
                    projectId, versionId, null, null, null,
                    null, null, CONVENTION_NODE_LIMIT);
            List<String> entityPaths = new ArrayList<>();
            for (GraphNode n : entities) {
                if (n.getSourcePath() != null && n.getSourcePath().contains("/entity/")) {
                    entityPaths.add(n.getSourcePath());
                }
            }
            conventions.put("entitySamplePaths", entityPaths);
            return objectMapper.writeValueAsString(conventions);
        } catch (Exception e) {
            log.warn("loadConventions failed for projectId={}: {}", projectId, e.getMessage());
            return "{}";
        }
    }

    // ==================== 可复用组件加载 ====================

    /**
     * 从图谱加载可复用组件（properties.reusable=true 的节点）。
     * <p>查询所有节点后过滤 properties 含 reusable=true 的节点，
     * 输出 [{name, path, reuseType, usageCount}] 的 JSON 供 LLM 参考。
     * 失败时返回空数组字符串。</p>
     */
    String loadReusableComponents(String projectId, String versionId) {
        try {
            // 可复用组件散落在多个 nodeType，无法直接按 type 过滤；扫描全量节点后过滤
            List<GraphNode> allNodes = neo4jGraphDao.queryNodes(
                    projectId, versionId, null, null, null,
                    null, null, REUSABLE_NODE_LIMIT);
            List<Map<String, Object>> reusable = new ArrayList<>();
            for (GraphNode n : allNodes) {
                Map<String, Object> props = parseProperties(n.getProperties());
                if (Boolean.TRUE.equals(props.get("reusable"))) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", n.getNodeName());
                    item.put("path", n.getSourcePath());
                    item.put("reuseType", props.get("reuseType"));
                    item.put("usageCount", props.get("usageCount"));
                    item.put("nodeKey", n.getNodeKey());
                    reusable.add(item);
                }
            }
            return objectMapper.writeValueAsString(reusable);
        } catch (Exception e) {
            log.warn("loadReusableComponents failed for projectId={}: {}", projectId, e.getMessage());
            return "[]";
        }
    }

    // ==================== 影响签名加载 ====================

    /**
     * 从 ImpactResult 的 impactedNodes 中提取关键节点的签名信息。
     * <p>查询 Neo4jGraphDao 获取节点的 properties（signature/parameters/returnType/fields 等），
     * 组装为 [{nodeKey, nodeType, nodeName, signature, fields}] 的 JSON 字符串。
     * 优先选择 depth <= 1 的节点（直接影响），最多提取前 20 个节点以避免 prompt 过长。
     * 失败时返回空数组字符串，不阻塞主流程。</p>
     *
     * @param impactResult 影响子图结果（可为 null）
     * @return 签名信息 JSON 字符串
     */
    String loadImpactSignatures(ImpactResult impactResult) {
        try {
            if (impactResult == null
                    || impactResult.getImpactedNodes() == null
                    || impactResult.getImpactedNodes().isEmpty()) {
                return "[]";
            }

            // 优先选择 depth <= 1 的节点（直接影响），不足时补充更深节点
            List<ImpactNode> directNodes = new ArrayList<>();
            List<ImpactNode> indirectNodes = new ArrayList<>();
            for (ImpactNode node : impactResult.getImpactedNodes()) {
                if (node.getDepth() <= 1) {
                    directNodes.add(node);
                } else {
                    indirectNodes.add(node);
                }
            }
            List<ImpactNode> candidates = new ArrayList<>(directNodes);
            candidates.addAll(indirectNodes);
            if (candidates.size() > IMPACT_SIGNATURE_NODE_LIMIT) {
                candidates = candidates.subList(0, IMPACT_SIGNATURE_NODE_LIMIT);
            }

            List<Map<String, Object>> signatures = new ArrayList<>();
            for (ImpactNode node : candidates) {
                Optional<GraphNode> graphNodeOpt = neo4jGraphDao.findNodeById(node.getNodeId());
                if (graphNodeOpt.isEmpty()) {
                    continue;
                }
                GraphNode graphNode = graphNodeOpt.get();
                Map<String, Object> props = parseProperties(graphNode.getProperties());

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("nodeKey", node.getNodeKey());
                item.put("nodeType", node.getNodeType());
                item.put("nodeName", node.getNodeName());
                item.put("signature", props.get("signature"));
                item.put("fields", props.get("fields"));
                signatures.add(item);
            }
            return objectMapper.writeValueAsString(signatures);
        } catch (Exception e) {
            log.warn("loadImpactSignatures failed: {}", e.getMessage());
            return "[]";
        }
    }

    // ==================== 辅助方法 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseProperties(String propertiesJson) {
        if (propertiesJson == null || propertiesJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(propertiesJson, Map.class);
            return parsed != null ? parsed : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJsonSafe(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize {}: {}", value.getClass().getSimpleName(), e.getMessage());
            return "{}";
        }
    }
}
