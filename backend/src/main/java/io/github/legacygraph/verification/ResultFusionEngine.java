package io.github.legacygraph.verification;

import io.github.legacygraph.builder.EvidenceGraphWriter;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 结果融合引擎 — 将外部验证工具的校验结果融合回本地图谱。
 * <p>
 * 四类融合策略：
 * <ul>
 *   <li>confirmedEdges：将本地对应边的 confidence 置为 1.0、status 置为 CONFIRMED</li>
 *   <li>missingEdges：通过 EvidenceGraphWriter 补写缺失边（EXTERNAL_VERIFY 来源，待确认）</li>
 *   <li>nodeProperties：将外部工具独有的节点属性写回本地节点（属性名前缀来源工具）</li>
 *   <li>suspiciousEdges：将可疑边的 status 降级为 PENDING_CONFIRM</li>
 * </ul>
 * </p>
 * <p>
 * 容错原则：单条融合失败（异常）计入 errors 但不影响整体；节点/边找不到时跳过并记 WARN，
 * 不计入 errors（属正常跳过而非错误）。
 * </p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ResultFusionEngine {

    private final Neo4jGraphDao neo4jGraphDao;
    private final EvidenceGraphWriter evidenceGraphWriter;

    private static final String SOURCE_TYPE_EXTERNAL_VERIFY = "EXTERNAL_VERIFY";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_PENDING_CONFIRM = "PENDING_CONFIRM";
    private static final BigDecimal MISSING_EDGE_CONFIDENCE = BigDecimal.valueOf(0.85);

    /**
     * 融合本地结果与外部验证结果。
     *
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @param results   外部验证结果列表
     * @return 融合统计
     */
    public FusionStats fuse(String projectId, String versionId, List<VerificationResult> results) {
        int confirmedCount = 0;
        int missingWritten = 0;
        int propertiesWritten = 0;
        int suspiciousMarked = 0;
        int errors = 0;

        if (results == null || results.isEmpty()) {
            log.debug("fuse: no verification results to fuse (projectId={}, versionId={})",
                    projectId, versionId);
            return FusionStats.builder().build();
        }

        for (VerificationResult result : results) {
            String adapterName = result != null ? result.getAdapterName() : "unknown";

            // 1. confirmedEdges — 确认边，提升置信度与状态
            if (result != null && result.getConfirmedEdges() != null) {
                for (VerifiedEdge ve : result.getConfirmedEdges()) {
                    try {
                        if (confirmEdge(projectId, versionId, ve)) {
                            confirmedCount++;
                        }
                    } catch (Exception e) {
                        errors++;
                        log.warn("fuse confirmedEdge failed (adapter={}, fromKey={}, toKey={}, edgeType={}): {}",
                                adapterName, ve.getFromNodeKey(), ve.getToNodeKey(),
                                ve.getEdgeType(), e.getMessage());
                    }
                }
            }

            // 2. missingEdges — 缺失边，补写
            if (result != null && result.getMissingEdges() != null) {
                for (VerifiedEdge ve : result.getMissingEdges()) {
                    try {
                        if (writeMissingEdge(projectId, versionId, ve)) {
                            missingWritten++;
                        }
                    } catch (Exception e) {
                        errors++;
                        log.warn("fuse missingEdge failed (adapter={}, fromKey={}, toKey={}, edgeType={}): {}",
                                adapterName, ve.getFromNodeKey(), ve.getToNodeKey(),
                                ve.getEdgeType(), e.getMessage());
                    }
                }
            }

            // 3. nodeProperties — 节点属性回写
            if (result != null && result.getNodeProperties() != null) {
                for (VerifiedNodeProperty vnp : result.getNodeProperties()) {
                    try {
                        if (writeNodeProperty(projectId, versionId, vnp)) {
                            propertiesWritten++;
                        }
                    } catch (Exception e) {
                        errors++;
                        log.warn("fuse nodeProperty failed (adapter={}, nodeKey={}, property={}): {}",
                                adapterName, vnp.getNodeKey(), vnp.getPropertyName(), e.getMessage());
                    }
                }
            }

            // 4. suspiciousEdges — 可疑边降级
            if (result != null && result.getSuspiciousEdges() != null) {
                for (VerifiedEdge ve : result.getSuspiciousEdges()) {
                    try {
                        if (markSuspiciousEdge(projectId, versionId, ve)) {
                            suspiciousMarked++;
                        }
                    } catch (Exception e) {
                        errors++;
                        log.warn("fuse suspiciousEdge failed (adapter={}, fromKey={}, toKey={}, edgeType={}): {}",
                                adapterName, ve.getFromNodeKey(), ve.getToNodeKey(),
                                ve.getEdgeType(), e.getMessage());
                    }
                }
            }
        }

        FusionStats finalStats = FusionStats.builder()
                .confirmedCount(confirmedCount)
                .missingWritten(missingWritten)
                .propertiesWritten(propertiesWritten)
                .suspiciousMarked(suspiciousMarked)
                .errors(errors)
                .build();
        log.info("fuse completed (projectId={}, versionId={}): {}", projectId, versionId, finalStats);
        return finalStats;
    }

    /**
     * 确认边：将本地对应边的 confidence 置为 1.0、status 置为 CONFIRMED。
     */
    private boolean confirmEdge(String projectId, String versionId, VerifiedEdge ve) {
        Optional<GraphEdge> localEdge = findLocalEdge(projectId, versionId, ve);
        if (localEdge.isEmpty()) {
            log.warn("confirmEdge: local edge not found, skip (fromKey={}, toKey={}, edgeType={})",
                    ve.getFromNodeKey(), ve.getToNodeKey(), ve.getEdgeType());
            return false;
        }
        String edgeId = localEdge.get().getId();
        neo4jGraphDao.setEdgeProperty(edgeId, "confidence", 1.0);
        neo4jGraphDao.setEdgeProperty(edgeId, "status", STATUS_CONFIRMED);
        return true;
    }

    /**
     * 写入缺失边：先解析 from/to 节点 ID，再通过 EvidenceGraphWriter.upsertEdge 补写
     * （sourceType=EXTERNAL_VERIFY, confidence=0.85, status=PENDING_CONFIRM）。
     */
    private boolean writeMissingEdge(String projectId, String versionId, VerifiedEdge ve) {
        String fromNodeId = resolveNodeId(projectId, versionId, ve.getFromNodeKey());
        if (fromNodeId == null) {
            log.warn("writeMissingEdge: fromNode not found, skip (fromKey={}, toKey={}, edgeType={})",
                    ve.getFromNodeKey(), ve.getToNodeKey(), ve.getEdgeType());
            return false;
        }
        String toNodeId = resolveNodeId(projectId, versionId, ve.getToNodeKey());
        if (toNodeId == null) {
            log.warn("writeMissingEdge: toNode not found, skip (fromKey={}, toKey={}, edgeType={})",
                    ve.getFromNodeKey(), ve.getToNodeKey(), ve.getEdgeType());
            return false;
        }
        String edgeKey = ve.getFromNodeKey() + "->" + ve.getToNodeKey() + ":" + ve.getEdgeType();
        GraphEdgeClaim claim = GraphEdgeClaim.builder()
                .projectId(projectId)
                .versionId(versionId)
                .fromNodeId(fromNodeId)
                .toNodeId(toNodeId)
                .edgeType(ve.getEdgeType())
                .edgeKey(edgeKey)
                .sourceType(SOURCE_TYPE_EXTERNAL_VERIFY)
                .confidence(MISSING_EDGE_CONFIDENCE)
                .status(STATUS_PENDING_CONFIRM)
                .build();
        evidenceGraphWriter.upsertEdge(claim);
        return true;
    }

    /**
     * 写入节点属性：通过 nodeKey 查找节点，属性名前缀来源工具名（如 "mcp.complexity"）。
     */
    private boolean writeNodeProperty(String projectId, String versionId, VerifiedNodeProperty vnp) {
        String nodeId = resolveNodeId(projectId, versionId, vnp.getNodeKey());
        if (nodeId == null) {
            log.warn("writeNodeProperty: node not found, skip (nodeKey={}, property={})",
                    vnp.getNodeKey(), vnp.getPropertyName());
            return false;
        }
        String propertyName = prefixWithSourceTool(vnp.getSourceTool(), vnp.getPropertyName());
        neo4jGraphDao.setNodeProperty(nodeId, propertyName, vnp.getPropertyValue());
        return true;
    }

    /**
     * 标记可疑边：将本地对应边的 status 降级为 PENDING_CONFIRM。
     */
    private boolean markSuspiciousEdge(String projectId, String versionId, VerifiedEdge ve) {
        Optional<GraphEdge> localEdge = findLocalEdge(projectId, versionId, ve);
        if (localEdge.isEmpty()) {
            log.warn("markSuspiciousEdge: local edge not found, skip (fromKey={}, toKey={}, edgeType={})",
                    ve.getFromNodeKey(), ve.getToNodeKey(), ve.getEdgeType());
            return false;
        }
        neo4jGraphDao.setEdgeProperty(localEdge.get().getId(), "status", STATUS_PENDING_CONFIRM);
        return true;
    }

    /**
     * 查找本地边：先按 nodeKey 解析 from/to 节点 ID，再 findEdge（edgeKey 传 null 不按键过滤）。
     */
    private Optional<GraphEdge> findLocalEdge(String projectId, String versionId, VerifiedEdge ve) {
        String fromNodeId = resolveNodeId(projectId, versionId, ve.getFromNodeKey());
        if (fromNodeId == null) {
            return Optional.empty();
        }
        String toNodeId = resolveNodeId(projectId, versionId, ve.getToNodeKey());
        if (toNodeId == null) {
            return Optional.empty();
        }
        return neo4jGraphDao.findEdge(projectId, versionId, fromNodeId, toNodeId,
                ve.getEdgeType(), null);
    }

    /**
     * 按 nodeKey 解析节点 ID（nodeType 传 null 不按类型过滤）。
     *
     * @return 节点 ID；找不到或 nodeKey 为 null 时返回 null
     */
    private String resolveNodeId(String projectId, String versionId, String nodeKey) {
        if (nodeKey == null) {
            return null;
        }
        return neo4jGraphDao.findNode(projectId, versionId, null, nodeKey)
                .map(GraphNode::getId)
                .orElse(null);
    }

    /**
     * 属性名前缀：来源工具名 + "." + 属性名（如 "mcp.complexity"）。
     * sourceTool 为空时直接返回原属性名。
     */
    private String prefixWithSourceTool(String sourceTool, String propertyName) {
        if (sourceTool == null || sourceTool.isBlank()) {
            return propertyName;
        }
        return sourceTool + "." + propertyName;
    }

    /**
     * 融合统计。
     */
    @Data
    @Builder
    public static class FusionStats {
        /** 确认的边数（已置 CONFIRMED + confidence=1.0） */
        private int confirmedCount;
        /** 补写的缺失边数 */
        private int missingWritten;
        /** 写回的节点属性数 */
        private int propertiesWritten;
        /** 标记为可疑（降级 PENDING_CONFIRM）的边数 */
        private int suspiciousMarked;
        /** 异常失败数（不含正常跳过） */
        private int errors;
    }
}
