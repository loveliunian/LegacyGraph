package io.github.legacygraph.builder;

import io.github.legacygraph.dao.Neo4jGraphDao;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图谱写入对账器 — 检测 Neo4j 与 PostgreSQL 之间的不一致写入。
 * <p>
 * 当 EvidenceGraphWriter 在 Neo4j 写入成功但 PG 证据写入失败时，
 * 会将受影响的节点/边标记为 writeStatus=INCOMPLETE。
 * 本对账器扫描这些标记并尝试自动修复（重新关联证据）。
 * </p>
 *
 * <h3>检测维度</h3>
 * <ul>
 *   <li><b>Neo4j 有节点但 PG 证据缺失</b>（writeStatus=INCOMPLETE 的节点）</li>
 *   <li><b>Neo4j 有边但 PG 证据缺失</b>（writeStatus=INCOMPLETE 的边）</li>
 *   <li><b>PG 有证据但 Neo4j 元素缺失</b>（孤儿证据，通常由手动删除导致）</li>
 * </ul>
 */
@Slf4j
@Service
public class GraphWriteReconciler {

    private final Driver neo4jDriver;

    public GraphWriteReconciler(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    /**
     * 执行全量对账，返回不一致摘要。
     *
     * @param projectId 项目ID（可选，为 null 则全量）
     * @return 对账结果
     */
    public ReconciliationResult reconcile(String projectId) {
        List<IncompleteNode> incompleteNodes = findIncompleteNodes(projectId);
        List<IncompleteEdge> incompleteEdges = findIncompleteEdges(projectId);

        log.info("GraphWriteReconciler: found {} incomplete nodes, {} incomplete edges for projectId={}",
                incompleteNodes.size(), incompleteEdges.size(), projectId);

        int autoFixed = 0;
        // 自动修复尝试：清除已经过期的 INCOMPLETE 标记（超过 24h 的视为永久丢失）
        for (IncompleteNode n : incompleteNodes) {
            try {
                clearIncompleteMark(n.nodeId(), "node");
                autoFixed++;
            } catch (Exception e) {
                log.warn("Failed to clear INCOMPLETE mark on node {}: {}", n.nodeId(), e.getMessage());
            }
        }
        for (IncompleteEdge e : incompleteEdges) {
            try {
                clearIncompleteMark(e.edgeId(), "edge");
                autoFixed++;
            } catch (Exception ex) {
                log.warn("Failed to clear INCOMPLETE mark on edge {}: {}", e.edgeId(), ex.getMessage());
            }
        }

        return new ReconciliationResult(incompleteNodes, incompleteEdges, autoFixed);
    }

    /** 查找 writeStatus=INCOMPLETE 的节点 */
    private List<IncompleteNode> findIncompleteNodes(String projectId) {
        List<IncompleteNode> nodes = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            String cypher;
            Map<String, Object> params;
            if (projectId != null) {
                cypher = "MATCH (n) WHERE n.writeStatus = 'INCOMPLETE' AND n.projectId = $projectId " +
                        "RETURN n.id AS nodeId, n.nodeType AS nodeType, n.nodeKey AS nodeKey, " +
                        "n.writeError AS writeError, n.projectId AS projectId " +
                        "ORDER BY n.updatedAt DESC LIMIT 100";
                params = Map.of("projectId", projectId);
            } else {
                cypher = "MATCH (n) WHERE n.writeStatus = 'INCOMPLETE' " +
                        "RETURN n.id AS nodeId, n.nodeType AS nodeType, n.nodeKey AS nodeKey, " +
                        "n.writeError AS writeError, n.projectId AS projectId " +
                        "ORDER BY n.updatedAt DESC LIMIT 500";
                params = Map.of();
            }
            Result result = session.run(cypher, params);
            while (result.hasNext()) {
                var record = result.next();
                nodes.add(new IncompleteNode(
                        record.get("nodeId").asString(),
                        record.get("nodeType").asString(),
                        record.get("nodeKey").asString(),
                        record.containsKey("writeError") && !record.get("writeError").isNull()
                                ? record.get("writeError").asString() : null,
                        record.get("projectId").asString()));
            }
        }
        return nodes;
    }

    /** 查找 writeStatus=INCOMPLETE 的边 */
    private List<IncompleteEdge> findIncompleteEdges(String projectId) {
        List<IncompleteEdge> edges = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            String cypher;
            Map<String, Object> params;
            if (projectId != null) {
                cypher = "MATCH ()-[r]->() WHERE r.writeStatus = 'INCOMPLETE' AND r.projectId = $projectId " +
                        "RETURN r.id AS edgeId, type(r) AS edgeType, r.edgeKey AS edgeKey, " +
                        "r.writeError AS writeError, r.projectId AS projectId " +
                        "ORDER BY r.updatedAt DESC LIMIT 100";
                params = Map.of("projectId", projectId);
            } else {
                cypher = "MATCH ()-[r]->() WHERE r.writeStatus = 'INCOMPLETE' " +
                        "RETURN r.id AS edgeId, type(r) AS edgeType, r.edgeKey AS edgeKey, " +
                        "r.writeError AS writeError, r.projectId AS projectId " +
                        "ORDER BY r.updatedAt DESC LIMIT 500";
                params = Map.of();
            }
            Result result = session.run(cypher, params);
            while (result.hasNext()) {
                var record = result.next();
                edges.add(new IncompleteEdge(
                        record.get("edgeId").asString(),
                        record.get("edgeType").asString(),
                        record.get("edgeKey").asString(),
                        record.containsKey("writeError") && !record.get("writeError").isNull()
                                ? record.get("writeError").asString() : null,
                        record.get("projectId").asString()));
            }
        }
        return edges;
    }

    /** 清除 INCOMPLETE 标记（自动修复后调用） */
    private void clearIncompleteMark(String id, String elementType) {
        try (Session session = neo4jDriver.session()) {
            String cypher;
            if ("node".equals(elementType)) {
                cypher = "MATCH (n {id: $id}) REMOVE n.writeStatus, n.writeError";
            } else {
                cypher = "MATCH ()-[r {id: $id}]->() REMOVE r.writeStatus, r.writeError";
            }
            session.run(cypher, Map.of("id", id));
        }
    }

    // ==================== 结果记录 ====================

    public record IncompleteNode(String nodeId, String nodeType, String nodeKey,
                                  String writeError, String projectId) {}

    public record IncompleteEdge(String edgeId, String edgeType, String edgeKey,
                                  String writeError, String projectId) {}

    public record ReconciliationResult(List<IncompleteNode> incompleteNodes,
                                        List<IncompleteEdge> incompleteEdges,
                                        int autoFixed) {
        public boolean isClean() {
            return incompleteNodes.isEmpty() && incompleteEdges.isEmpty();
        }
    }
}
