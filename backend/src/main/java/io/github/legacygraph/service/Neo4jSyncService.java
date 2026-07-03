package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Neo4j 同步服务（已废弃：图谱数据直接写入 Neo4j，不再需要从 PG 同步）
 * 保留用于兼容旧代码调用，实际工作委托给 Neo4jGraphDao。
 */
@Slf4j
@Service
public class Neo4jSyncService {

    private final Neo4jGraphDao neo4jGraphDao;

    public Neo4jSyncService(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /** 清理 Neo4j 图谱数据（用于重新扫描前） */
    public void syncGraph(String projectId, String versionId) {
        log.info("Clearing Neo4j graph before rescan: projectId={}, versionId={}", projectId, versionId);
        neo4jGraphDao.deleteGraph(projectId, versionId);
    }

    public void syncDeleteNode(String projectId, String versionId, String nodeId) {
        neo4jGraphDao.deleteNode(projectId, versionId, nodeId);
    }

    public void syncDeleteNodes(String projectId, String versionId, List<String> nodeIds) {
        for (String nodeId : nodeIds) {
            neo4jGraphDao.deleteNode(projectId, versionId, nodeId);
        }
    }

    public void incrementalSyncNodes(String projectId, String versionId, List<GraphNode> nodes) {
        for (GraphNode node : nodes) {
            if (node.getDeleted() != null && node.getDeleted() == 1) continue;
            neo4jGraphDao.createNode(node);
        }
    }

    public void createConstraints() {
        neo4jGraphDao.createConstraints();
    }

    public void createIndexes() {
        neo4jGraphDao.createIndexes();
    }
}
