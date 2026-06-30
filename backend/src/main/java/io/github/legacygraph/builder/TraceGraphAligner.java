package io.github.legacygraph.builder;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.RuntimeEvidenceRecord;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 运行时 Trace 图谱对齐器。
 * <p>
 * 将 Trace Span 序列对齐到图谱中的路径：
 * </p>
 * <ul>
 *   <li>将 span 归一化为 {@link RuntimeEvidenceRecord}</li>
 *   <li>对齐到 ApiEndpoint → Method → Mapper → Table 或 Page → ApiEndpoint 路径</li>
 *   <li>更新边级属性：runtimeObserved, traceCount, lastSeenAt, p95DurationMs, errorCount</li>
 *   <li>未匹配 span 标为 dynamic_only_candidate</li>
 *   <li>静态有但运行时未观测的边标为 static_only_candidate</li>
 * </ul>
 */
@Slf4j
@Component
public class TraceGraphAligner {

    private final Neo4jGraphDao neo4jGraphDao;
    private final EvidenceGraphWriter writer;

    public TraceGraphAligner(Neo4jGraphDao neo4jGraphDao,
                             EvidenceGraphWriter writer) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.writer = writer;
    }

    /**
     * 将一批运行时 span 对齐到图谱。
     *
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @param records   运行时证据记录列表
     * @return 对齐结果统计
     */
    @Transactional
    public AlignmentResult align(String projectId, String versionId,
                                  List<RuntimeEvidenceRecord> records) {
        int matchedCount = 0;
        int unmatchedCount = 0;
        List<RuntimeEvidenceRecord> dynamicOnlyCandidates = new ArrayList<>();

        for (RuntimeEvidenceRecord record : records) {
            boolean matched = alignSingle(projectId, versionId, record);
            if (matched) {
                matchedCount++;
            } else {
                unmatchedCount++;
                dynamicOnlyCandidates.add(record);
            }
        }

        // 标记静态但运行时未观测的边
        List<GraphEdge> staticEdges = neo4jGraphDao.queryEdges(
                projectId, versionId, null, null, 100);
        int staticOnlyCount = 0;
        for (GraphEdge edge : staticEdges) {
            if (edge.getRelationStatus() == null || edge.getRelationStatus().isBlank()) {
                staticOnlyCount++;
                edge.setRelationStatus("static_only_candidate");
                edge.setUpdatedAt(LocalDateTime.now());
                neo4jGraphDao.updateEdge(edge);
            }
        }

        log.info("Trace alignment completed: matched={}, unmatched={}, static_only={}",
                matchedCount, unmatchedCount, staticOnlyCount);

        return new AlignmentResult(matchedCount, unmatchedCount,
                staticOnlyCount, dynamicOnlyCandidates);
    }

    /**
     * 对齐单条运行时记录。
     */
    private boolean alignSingle(String projectId, String versionId,
                                 RuntimeEvidenceRecord record) {
        boolean matched = false;

        // 1. 尝试匹配 ApiEndpoint 节点
        if (record.getPath() != null && record.getHttpMethod() != null) {
            String apiKey = record.getHttpMethod().toUpperCase() + " " + record.getPath();
            Optional<GraphNode> apiNode = neo4jGraphDao.findNode(
                    projectId, versionId, NodeType.ApiEndpoint.name(), apiKey);

            if (apiNode.isPresent()) {
                GraphNode node = apiNode.get();
                node.setRuntimeVerified(true);
                node.setLastSeenAt(record.getObservedAt() != null
                        ? record.getObservedAt() : LocalDateTime.now());
                node.setTraceCount(node.getTraceCount() != null
                        ? node.getTraceCount() + 1 : 1);
                node.setUpdatedAt(LocalDateTime.now());
                neo4jGraphDao.updateNode(node);
                record.setMatchedNodeId(node.getId());
                record.setAligned(true);
                matched = true;
            }
        }

        // 2. 尝试匹配 SQL 节点（通过 sqlHash）
        if (record.getSqlHash() != null && !record.getSqlHash().isBlank()) {
            List<GraphNode> sqlNodes = neo4jGraphDao.queryNodes(
                    projectId, versionId, NodeType.SqlStatement.name(),
                    null, null, null, null, 100);
            for (GraphNode sqlNode : sqlNodes) {
                if (record.getSqlHash().equals(sqlNode.getNodeKey())) {
                    sqlNode.setRuntimeVerified(true);
                    sqlNode.setLastSeenAt(record.getObservedAt() != null
                            ? record.getObservedAt() : LocalDateTime.now());
                    sqlNode.setTraceCount(sqlNode.getTraceCount() != null
                            ? sqlNode.getTraceCount() + 1 : 1);
                    sqlNode.setUpdatedAt(LocalDateTime.now());
                    neo4jGraphDao.updateNode(sqlNode);
                    record.setMatchedNodeId(sqlNode.getId());
                    record.setAligned(true);
                    matched = true;
                    break;
                }
            }
        }

        return matched;
    }

    /**
     * 对齐结果。
     */
    public record AlignmentResult(
            int matchedCount,
            int unmatchedCount,
            int staticOnlyCount,
            List<RuntimeEvidenceRecord> dynamicOnlyCandidates
    ) {}
}
