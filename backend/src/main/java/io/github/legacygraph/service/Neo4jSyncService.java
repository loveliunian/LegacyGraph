package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Neo4j 同步服务
 * 将PostgreSQL中的图谱节点和关系同步到Neo4j图数据库
 */
@Slf4j
@Service
public class Neo4jSyncService {

    private final GraphNodeRepository graphNodeRepository;
    private final GraphEdgeRepository graphEdgeRepository;
    private final Driver neo4jDriver;

    public Neo4jSyncService(GraphNodeRepository graphNodeRepository,
                           GraphEdgeRepository graphEdgeRepository,
                           Driver neo4jDriver) {
        this.graphNodeRepository = graphNodeRepository;
        this.graphEdgeRepository = graphEdgeRepository;
        this.neo4jDriver = neo4jDriver;
    }

    /**
     * 同步整个版本的图谱到Neo4j
     */
    @Transactional
    public void syncGraph(String projectId, String versionId) {
        log.info("Starting sync graph to Neo4j: projectId={}, versionId={}", projectId, versionId);

        try (Session session = neo4jDriver.session()) {
            // 删除该版本旧数据
            deleteExistingGraph(session, projectId, versionId);

            // 批量插入节点
            List<GraphNode> nodes = graphNodeRepository.lambdaQuery()
                    .eq(GraphNode::getProjectId, projectId)
                    .eq(GraphNode::getVersionId, versionId)
                    .eq(GraphNode::getStatus, "CONFIRMED")
                    .list();

            int nodeCount = 0;
            try (Transaction tx = session.beginTransaction()) {
                for (GraphNode node : nodes) {
                    createNode(tx, node);
                    nodeCount++;
                    if (nodeCount % 1000 == 0) {
                        log.info("Synced {} nodes", nodeCount);
                        tx.commit();
                        tx.close();
                        tx = session.beginTransaction();
                    }
                }
                tx.commit();
            }
            log.info("Synced total {} nodes", nodeCount);

            // 批量插入关系
            List<GraphEdge> edges = graphEdgeRepository.lambdaQuery()
                    .eq(GraphEdge::getProjectId, projectId)
                    .eq(GraphEdge::getVersionId, versionId)
                    .eq(GraphEdge::getStatus, "CONFIRMED")
                    .list();

            int edgeCount = 0;
            try (Transaction tx = session.beginTransaction()) {
                for (GraphEdge edge : edges) {
                    createEdge(tx, edge);
                    edgeCount++;
                    if (edgeCount % 1000 == 0) {
                        log.info("Synced {} edges", edgeCount);
                        tx.commit();
                        tx.close();
                        tx = session.beginTransaction();
                    }
                }
                tx.commit();
            }
            log.info("Synced total {} edges", edgeCount);
        }

        log.info("Completed sync graph to Neo4j");
    }

    /**
     * 删除该版本已存在的图数据
     */
    private void deleteExistingGraph(Session session, String projectId, String versionId) {
        String cypher = "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId DELETE n";
        session.run(cypher, Map.of("projectId", projectId, "versionId", versionId));
        log.info("Deleted existing graph data for projectId={}, versionId={}", projectId, versionId);
    }

    /**
     * 创建节点
     */
    private void createNode(Transaction tx, GraphNode node) {
        // 使用nodeType作为标签
        String label = node.getNodeType();
        String cypher = String.format(
                "CREATE (n:%s {id: $id, projectId: $projectId, versionId: $versionId, nodeKey: $nodeKey, nodeName: $nodeName, displayName: $displayName, description: $description, confidence: $confidence, status: $status})",
                label
        );

        Map<String, Object> params = Map.of(
                "id", node.getId(),
                "projectId", node.getProjectId(),
                "versionId", node.getVersionId(),
                "nodeKey", node.getNodeKey(),
                "nodeName", node.getNodeName() != null ? node.getNodeName() : "",
                "displayName", node.getDisplayName() != null ? node.getDisplayName() : "",
                "description", node.getDescription() != null ? node.getDescription() : "",
                "confidence", node.getConfidence(),
                "status", node.getStatus()
        );

        tx.run(cypher, params);
    }

    /**
     * 创建关系
     */
    private void createEdge(Transaction tx, GraphEdge edge) {
        String cypher = """
                MATCH (from) WHERE from.id = $fromId
                MATCH (to) WHERE to.id = $toId
                CREATE (from)-[r:%s {id: $id, projectId: $projectId, versionId: $versionId, edgeKey: $edgeKey, confidence: $confidence}]->(to)
                """.formatted(edge.getEdgeType());

        Map<String, Object> params = Map.of(
                "id", edge.getId(),
                "fromId", edge.getFromNodeId(),
                "toId", edge.getToNodeId(),
                "projectId", edge.getProjectId(),
                "versionId", edge.getVersionId(),
                "edgeKey", edge.getEdgeKey() != null ? edge.getEdgeKey() : "",
                "confidence", edge.getConfidence()
        );

        tx.run(cypher, params);
    }

    /**
     * 创建约束（需要在初始化时执行）
     */
    public void createConstraints() {
        try (Session session = neo4jDriver.session()) {
            // 创建各个类型的唯一约束
            String[] nodeTypes = {
                    "Project", "ApiEndpoint", "Table", "Method", "Controller", "Service", "Mapper"
            };

            for (String type : nodeTypes) {
                String cypher = String.format(
                        "CREATE CONSTRAINT %s_key IF NOT EXISTS FOR (n:%s) REQUIRE n.nodeKey IS UNIQUE",
                        type.toLowerCase() + "_key",
                        type
                );
                session.run(cypher);
                log.info("Created constraint for {}", type);
            }
        }
    }

    /**
     * 同步单个节点删除 - 关系数据库删除后，同步Neo4j删除
     */
    public void syncDeleteNode(String projectId, String versionId, String nodeId) {
        try (Session session = neo4jDriver.session()) {
            String cypher = "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId AND n.id = $nodeId DETACH DELETE n";
            session.run(cypher, Map.of("projectId", projectId, "versionId", versionId, "nodeId", nodeId));
            log.info("Synced node deletion: nodeId={}", nodeId);
        }
    }

    /**
     * 批量同步节点删除
     */
    public void syncDeleteNodes(String projectId, String versionId, List<String> nodeIds) {
        try (Session session = neo4jDriver.session()) {
            try (Transaction tx = session.beginTransaction()) {
                for (String nodeId : nodeIds) {
                    String cypher = "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId AND n.id = $nodeId DETACH DELETE n";
                    tx.run(cypher, Map.of("projectId", projectId, "versionId", versionId, "nodeId", nodeId));
                }
                tx.commit();
            }
            log.info("Synced batch node deletion: {} nodes", nodeIds.size());
        }
    }

    /**
     * 增量同步新增节点 - 图谱合并后使用
     */
    public void incrementalSyncNodes(String projectId, String versionId, List<GraphNode> nodes) {
        if (nodes.isEmpty()) return;

        try (Session session = neo4jDriver.session()) {
            int count = 0;
            try (Transaction tx = session.beginTransaction()) {
                for (GraphNode node : nodes) {
                    // 如果节点已经删除，跳过
                    if (node.getDeleted() != null && node.getDeleted() == 1) {
                        continue;
                    }
                    createNode(tx, node);
                    count++;
                    if (count % 1000 == 0) {
                        tx.commit();
                        tx.close();
                    }
                }
                if (count > 0) {
                    tx.commit();
                }
            }
            log.info("Incremental sync completed: {} new nodes", count);
        }
    }

    /**
     * 创建索引提高查询性能
     */
    public void createIndexes() {
        try (Session session = neo4jDriver.session()) {
            // 为常见查询字段创建索引
            session.run("CREATE INDEX project_version_idx IF NOT EXISTS FOR (n) ON (n.projectId, n.versionId)");
            session.run("CREATE INDEX node_id_idx IF NOT EXISTS FOR (n) ON (n.id)");
            log.info("Created Neo4j indexes for projectId, versionId, id");
        }
    }
}
