package io.github.legacygraph.service.requirement;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.dto.RequirementItemDTO;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 需求图谱构建器（Task 6）。
 * <p>构建 Requirement → RequirementItem → AcceptanceCriterion/Constraint 节点和边，
 * 直写 Neo4j（{@link Neo4jGraphDao#createNode} / {@link Neo4jGraphDao#createEdge}）。</p>
 */
@Slf4j
@Service
public class RequirementGraphBuilder {

    private final Neo4jGraphDao neo4jGraphDao;

    public RequirementGraphBuilder(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * 构建需求图谱。
     *
     * @param projectId     项目 ID
     * @param versionId     扫描版本 ID（可为 null）
     * @param requirementId 需求标识，用于 nodeKey 去重前缀
     * @param analysis      LLM 抽取结果
     * @return 构建结果（节点 ID 列表 + 条目数）
     */
    public BuildResult build(String projectId, String versionId,
                             String requirementId, RequirementAnalysis analysis) {
        List<String> nodeIds = new ArrayList<>();
        if (analysis == null) {
            return new BuildResult(nodeIds, 0);
        }

        String goal = analysis.getGoal() != null ? analysis.getGoal() : "需求";

        // 1. Requirement 节点
        GraphNode reqNode = createNode(projectId, versionId,
                NodeType.Requirement.name(),
                "req:" + requirementId,
                goal,
                goal,
                SourceType.DOC_AI.name());
        nodeIds.add(reqNode.getId());

        // openQuestions 作为节点属性存储（换行分隔，便于追溯）
        if (analysis.getOpenQuestions() != null && !analysis.getOpenQuestions().isEmpty()) {
            neo4jGraphDao.setNodeProperty(reqNode.getId(), "openQuestions",
                    String.join("\n", analysis.getOpenQuestions()));
        }

        // 2. RequirementItem → AcceptanceCriterion / Constraint
        int itemCount = 0;
        if (analysis.getItems() != null) {
            for (RequirementItemDTO item : analysis.getItems()) {
                String code = item.getCode() != null ? item.getCode() : "R" + (itemCount + 1);
                String itemKey = "req:" + requirementId + ":" + code;
                String itemText = item.getText() != null ? item.getText() : "";
                GraphNode itemNode = createNode(projectId, versionId,
                        NodeType.RequirementItem.name(),
                        itemKey,
                        code + " " + itemText,
                        itemText,
                        SourceType.DOC_AI.name());
                nodeIds.add(itemNode.getId());

                createEdge(projectId, versionId,
                        reqNode.getId(), itemNode.getId(),
                        EdgeType.HAS_ITEM.name(),
                        reqNode.getNodeKey() + "->HAS_ITEM->" + itemNode.getNodeKey(),
                        SourceType.DOC_AI.name());
                itemCount++;

                // 验收条件
                if (item.getAcceptanceCriteria() != null) {
                    int idx = 0;
                    for (String ac : item.getAcceptanceCriteria()) {
                        if (ac == null || ac.isBlank()) {
                            continue;
                        }
                        GraphNode acNode = createNode(projectId, versionId,
                                NodeType.AcceptanceCriterion.name(),
                                itemKey + ":ac:" + idx,
                                ac,
                                ac,
                                SourceType.DOC_AI.name());
                        nodeIds.add(acNode.getId());
                        createEdge(projectId, versionId,
                                itemNode.getId(), acNode.getId(),
                                EdgeType.HAS_ACCEPTANCE_CRITERION.name(),
                                itemNode.getNodeKey() + "->HAS_AC->" + acNode.getNodeKey(),
                                SourceType.DOC_AI.name());
                        idx++;
                    }
                }

                // 约束
                if (item.getConstraints() != null) {
                    int idx = 0;
                    for (String c : item.getConstraints()) {
                        if (c == null || c.isBlank()) {
                            continue;
                        }
                        GraphNode cNode = createNode(projectId, versionId,
                                NodeType.Constraint.name(),
                                itemKey + ":cons:" + idx,
                                c,
                                c,
                                SourceType.DOC_AI.name());
                        nodeIds.add(cNode.getId());
                        createEdge(projectId, versionId,
                                itemNode.getId(), cNode.getId(),
                                EdgeType.HAS_CONSTRAINT.name(),
                                itemNode.getNodeKey() + "->HAS_CONSTRAINT->" + cNode.getNodeKey(),
                                SourceType.DOC_AI.name());
                        idx++;
                    }
                }
            }
        }

        log.info("Requirement graph built: projectId={}, requirementId={}, nodes={}, items={}",
                projectId, requirementId, nodeIds.size(), itemCount);
        return new BuildResult(nodeIds, itemCount);
    }

    private GraphNode createNode(String projectId, String versionId, String nodeType, String nodeKey,
                                 String nodeName, String description, String sourceType) {
        GraphNode node = new GraphNode();
        node.setId(IdUtil.fastUUID());
        node.setProjectId(projectId);
        node.setVersionId(versionId);
        node.setNodeType(nodeType);
        node.setNodeKey(nodeKey);
        node.setNodeName(nodeName);
        node.setDisplayName(nodeName);
        node.setDescription(description);
        node.setSourceType(sourceType);
        node.setConfidence(BigDecimal.valueOf(0.8));
        node.setStatus(NodeStatus.PENDING_CONFIRM.name());
        node.setProperties("{}");
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        return neo4jGraphDao.createNode(node);
    }

    private GraphEdge createEdge(String projectId, String versionId,
                                 String fromNodeId, String toNodeId,
                                 String edgeType, String edgeKey, String sourceType) {
        GraphEdge edge = new GraphEdge();
        edge.setId(IdUtil.fastUUID());
        edge.setProjectId(projectId);
        edge.setVersionId(versionId);
        edge.setFromNodeId(fromNodeId);
        edge.setToNodeId(toNodeId);
        edge.setEdgeType(edgeType);
        edge.setEdgeKey(edgeKey);
        edge.setSourceType(sourceType);
        edge.setConfidence(BigDecimal.valueOf(0.8));
        edge.setStatus(NodeStatus.PENDING_CONFIRM.name());
        edge.setProperties("{}");
        edge.setCreatedAt(LocalDateTime.now());
        edge.setUpdatedAt(LocalDateTime.now());
        return neo4jGraphDao.createEdge(edge);
    }

    /** 图谱构建结果 */
    public record BuildResult(List<String> nodeIds, int itemCount) {}
}
