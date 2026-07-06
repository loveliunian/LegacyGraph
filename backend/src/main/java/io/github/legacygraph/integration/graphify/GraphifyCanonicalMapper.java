package io.github.legacygraph.integration.graphify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dto.graph.EvidenceRecord;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.dto.graph.GraphWriteIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Graphify graph.json 到 LegacyGraph 规范模型的映射器。
 * <p>
 * 映射规则：
 * <ul>
 *   <li>节点类型：根据 file_type 和 label 推断 NodeType</li>
 *   <li>边类型：根据 relation 推断 EdgeType</li>
 *   <li>置信度：EXTRACTED → 0.95, INFERRED → confidence_score, AMBIGUOUS → 0.45</li>
 *   <li>状态：EXTRACTED → CONFIRMED, INFERRED/AMBIGUOUS → PENDING_CONFIRM</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphifyCanonicalMapper {

    private final ObjectMapper objectMapper;

    /**
     * 将 Graphify graph 映射为 LegacyGraph 写入意图。
     *
     * @param graph     Graphify 解析结果
     * @param projectId 项目 ID
     * @param versionId 版本 ID
     * @return 映射结果，包含 write intent 和 warnings
     */
    public MapResult map(GraphifyGraphJson graph, String projectId, String versionId) {
        List<String> warnings = new ArrayList<>();
        List<GraphNodeClaim> nodeClaims = new ArrayList<>();
        List<GraphEdgeClaim> edgeClaims = new ArrayList<>();
        List<EvidenceRecord> evidenceRecords = new ArrayList<>();

        // 1. 映射节点
        Map<String, String> nodeIdToKey = new HashMap<>();
        for (GraphifyGraphJson.Node node : graph.nodes()) {
            String nodeKey = "graphify:" + node.id();
            nodeIdToKey.put(node.id(), nodeKey);

            NodeType nodeType = inferNodeType(node);
            SourceType sourceType = inferNodeSourceType(node);

            try {
                GraphNodeClaim claim = GraphNodeClaim.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .nodeKey(nodeKey)
                        .nodeType(nodeType.name())
                        .nodeName(node.label() != null ? node.label() : node.id())
                        .sourceType(sourceType.name())
                        .status(NodeStatus.CONFIRMED.name())
                        .properties(objectMapper.writeValueAsString(buildNodeProperties(node)))
                        .idempotencyKey(nodeKey)
                        .build();

                nodeClaims.add(claim);

                // 生成证据记录
                EvidenceRecord evidence = EvidenceRecord.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .sourcePath(node.sourceFile())
                        .sourceName(node.label())
                        .evidenceType("GRAPHIFY_NODE")
                        .content("Graphify node: " + node.label())
                        .build();
                evidenceRecords.add(evidence);
            } catch (Exception e) {
                warnings.add("节点映射失败: " + node.id() + " - " + e.getMessage());
            }
        }

        // 2. 映射边
        for (GraphifyGraphJson.Edge edge : graph.resolvedEdges()) {
            String fromNodeKey = nodeIdToKey.get(edge.source());
            String toNodeKey = nodeIdToKey.get(edge.target());

            if (fromNodeKey == null || toNodeKey == null) {
                warnings.add("边引用了不存在的节点: " + edge.source() + " -> " + edge.target());
                continue;
            }

            EdgeType edgeType = inferEdgeType(edge.relation());
            SourceType sourceType = inferEdgeSourceType(edge);
            double confidence = inferConfidence(edge);
            String status = inferEdgeStatus(edge);

            String edgeKey = generateEdgeKey(fromNodeKey, toNodeKey, edgeType);

            try {
                GraphEdgeClaim claim = GraphEdgeClaim.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .fromNodeKey(fromNodeKey)
                        .toNodeKey(toNodeKey)
                        .edgeType(edgeType.name())
                        .edgeKey(edgeKey)
                        .sourceType(sourceType.name())
                        .confidence(BigDecimal.valueOf(confidence))
                        .status(status)
                        .properties(objectMapper.writeValueAsString(buildEdgeProperties(edge)))
                        .idempotencyKey(edgeKey)
                        .build();

                edgeClaims.add(claim);

                // 生成证据记录
                EvidenceRecord evidence = EvidenceRecord.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .sourcePath(edge.source())
                        .sourceName(edge.relation())
                        .evidenceType("GRAPHIFY_EDGE")
                        .content("Graphify edge: " + edge.relation())
                        .build();
                evidenceRecords.add(evidence);
            } catch (Exception e) {
                warnings.add("边映射失败: " + edge.source() + " -> " + edge.target() + " - " + e.getMessage());
            }
        }

        // 3. 构造 write intent
        String graphHash = computeGraphHash(graph);
        GraphWriteIntent intent = GraphWriteIntent.builder()
                .idempotencyKey("graphify:" + projectId + ":" + versionId + ":" + graphHash)
                .projectId(projectId)
                .versionId(versionId)
                .nodeClaims(nodeClaims)
                .edgeClaims(edgeClaims)
                .evidenceRecords(evidenceRecords)
                .source("SCAN")
                .build();

        log.info("Graphify 映射完成: {} nodes, {} edges, {} evidence, {} warnings",
                nodeClaims.size(), edgeClaims.size(), evidenceRecords.size(), warnings.size());

        return new MapResult(intent, warnings);
    }

    /**
     * 推断节点类型。
     */
    private NodeType inferNodeType(GraphifyGraphJson.Node node) {
        String fileType = node.fileType();
        String label = node.label();

        if (fileType != null) {
            return switch (fileType.toLowerCase()) {
                case "code" -> inferNodeTypeFromLabel(label);
                case "sql" -> NodeType.Table;
                case "vue", "jsx", "tsx" -> NodeType.Page;
                default -> NodeType.Feature;
            };
        }

        return inferNodeTypeFromLabel(label);
    }

    /**
     * 根据 label 推断节点类型。
     */
    private NodeType inferNodeTypeFromLabel(String label) {
        if (label == null) {
            return NodeType.Feature;
        }

        String lower = label.toLowerCase();
        if (lower.contains("controller")) return NodeType.Controller;
        if (lower.contains("service")) return NodeType.Service;
        if (lower.contains("repository") || lower.contains("mapper")) return NodeType.Mapper;
        if (lower.contains("entity") || lower.contains("model")) return NodeType.BusinessObject;
        if (lower.contains("config")) return NodeType.ConfigItem;

        return NodeType.Method;
    }

    /**
     * 推断节点来源类型。
     */
    private SourceType inferNodeSourceType(GraphifyGraphJson.Node node) {
        // Graphify 节点默认使用 AST 抽取
        return SourceType.GRAPHIFY_AST;
    }

    /**
     * 推断边类型。
     */
    private EdgeType inferEdgeType(String relation) {
        if (relation == null) {
            return EdgeType.USES;
        }

        String lower = relation.toLowerCase();
        return switch (lower) {
            case "calls", "invokes" -> EdgeType.CALLS;
            case "extends", "inherits", "implements" -> EdgeType.IMPLEMENTED_BY;
            case "imports" -> EdgeType.USES;
            case "uses", "depends_on" -> EdgeType.USES;
            case "reads_from", "reads" -> EdgeType.READS;
            case "writes_to", "writes" -> EdgeType.WRITES;
            case "contains", "has" -> EdgeType.CONTAINS;
            default -> EdgeType.USES;
        };
    }

    /**
     * 推断边来源类型。
     */
    private SourceType inferEdgeSourceType(GraphifyGraphJson.Edge edge) {
        String confidence = edge.confidence();
        if (confidence != null && confidence.equalsIgnoreCase("INFERRED")) {
            return SourceType.GRAPHIFY_SEMANTIC;
        }
        return SourceType.GRAPHIFY_AST;
    }

    /**
     * 推断置信度。
     */
    private double inferConfidence(GraphifyGraphJson.Edge edge) {
        String confidence = edge.confidence();
        if (confidence == null) {
            return 0.95; // 默认高置信度
        }

        return switch (confidence.toUpperCase()) {
            case "EXTRACTED" -> 0.95;
            case "INFERRED" -> edge.confidenceScore() != null ? edge.confidenceScore() : 0.75;
            case "AMBIGUOUS" -> 0.45;
            default -> 0.75;
        };
    }

    /**
     * 推断边状态。
     */
    private String inferEdgeStatus(GraphifyGraphJson.Edge edge) {
        String confidence = edge.confidence();
        if (confidence == null) {
            return NodeStatus.CONFIRMED.name();
        }

        return switch (confidence.toUpperCase()) {
            case "EXTRACTED" -> NodeStatus.CONFIRMED.name();
            case "INFERRED", "AMBIGUOUS" -> NodeStatus.PENDING_CONFIRM.name();
            default -> NodeStatus.CONFIRMED.name();
        };
    }

    /**
     * 构造节点属性。
     */
    private Map<String, Object> buildNodeProperties(GraphifyGraphJson.Node node) {
        Map<String, Object> props = new HashMap<>();
        props.put("graphifyId", node.id());
        if (node.fileType() != null) props.put("fileType", node.fileType());
        if (node.community() != null) props.put("community", node.community());
        if (node.communityName() != null) props.put("communityName", node.communityName());
        if (node.normLabel() != null) props.put("normLabel", node.normLabel());
        return props;
    }

    /**
     * 构造边属性。
     */
    private Map<String, Object> buildEdgeProperties(GraphifyGraphJson.Edge edge) {
        Map<String, Object> props = new HashMap<>();
        props.put("rawRelation", edge.relation());
        if (edge.confidence() != null) props.put("confidence", edge.confidence());
        if (edge.confidenceScore() != null) props.put("confidenceScore", edge.confidenceScore());
        return props;
    }

    /**
     * 生成边 key。
     */
    private String generateEdgeKey(String fromNodeKey, String toNodeKey, EdgeType edgeType) {
        return edgeType.name() + ":" + fromNodeKey + "->" + toNodeKey;
    }

    /**
     * 计算 graph hash（用于幂等性）。
     */
    private String computeGraphHash(GraphifyGraphJson graph) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(graph.nodes().size()).getBytes());
            digest.update(String.valueOf(graph.resolvedEdges().size()).getBytes());
            if (graph.builtAtCommit() != null) {
                digest.update(graph.builtAtCommit().getBytes());
            }
            byte[] hash = digest.digest();
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    /**
     * 映射结果。
     */
    public record MapResult(GraphWriteIntent intent, List<String> warnings) {}
}
