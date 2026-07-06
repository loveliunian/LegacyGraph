package io.github.legacygraph.dao;

import io.github.legacygraph.service.system.CacheService;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

import static io.github.legacygraph.dao.Neo4jConversions.*;

/**
 * Neo4j Admin Repository — 负责删除、清理等管理操作。
 */
@Slf4j
@Component
public class Neo4jAdminRepository {

    private final Driver neo4jDriver;

    @Autowired(required = false)
    private CacheService cacheService;

    public Neo4jAdminRepository(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    /** 删除指定版本的整个子图（DETACH DELETE） */
    public void deleteGraph(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            session.run(
                    "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId DETACH DELETE n",
                    Map.of("projectId", projectId, "versionId", normalizeId(versionId)));
            log.info("Deleted Neo4j graph: projectId={}, versionId={}", projectId, versionId);
        }
    }

    /** 删除指定项目的全部图谱数据（DETACH DELETE） */
    public void deleteProjectGraph(String projectId) {
        try (Session session = neo4jDriver.session()) {
            session.run(
                    "MATCH (n) WHERE n.projectId = $projectId DETACH DELETE n",
                    Map.of("projectId", projectId));
            log.info("Deleted all Neo4j graph for project: projectId={}", projectId);
        }
    }

    /** 删除单个节点 */
    public void deleteNode(String projectId, String versionId, String nodeId) {
        try (Session session = neo4jDriver.session()) {
            session.run(
                    "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId AND n.id = $nodeId DETACH DELETE n",
                    Map.of("projectId", projectId, "versionId", normalizeId(versionId), "nodeId", nodeId));
        }
        evictNodeCache(cacheService, nodeId);
    }

    /**
     * 按 sourcePath 删除指定版本中来自某个源文件的所有节点（用于增量删除）。
     * @return 删除的节点数量
     */
    public int deleteNodesBySourcePath(String projectId, String versionId, String sourcePath) {
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(
                    "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId AND n.sourcePath = $sourcePath " +
                    "WITH n " +
                    "DETACH DELETE n " +
                    "RETURN count(n) AS deletedCount",
                    Map.of("projectId", projectId, "versionId", normalizeId(versionId), "sourcePath", sourcePath));
            if (result.hasNext()) {
                int count = result.next().get("deletedCount").asInt(0);
                if (count > 0) {
                    log.info("Deleted {} nodes by sourcePath: projectId={}, versionId={}, path={}",
                            count, projectId, versionId, sourcePath);
                }
                return count;
            }
            return 0;
        }
    }

    /**
     * 批量按 sourcePath 列表删除节点（用于增量删除场景）。
     * @return 删除的节点总数
     */
    public int deleteNodesBySourcePaths(String projectId, String versionId, java.util.List<String> sourcePaths) {
        if (sourcePaths == null || sourcePaths.isEmpty()) return 0;
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(
                    "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId AND n.sourcePath IN $sourcePaths " +
                    "WITH n " +
                    "DETACH DELETE n " +
                    "RETURN count(n) AS deletedCount",
                    Map.of("projectId", projectId, "versionId", normalizeId(versionId), "sourcePaths", sourcePaths));
            if (result.hasNext()) {
                int count = result.next().get("deletedCount").asInt(0);
                log.info("Batch deleted {} nodes by sourcePaths: projectId={}, versionId={}, pathCount={}",
                        count, projectId, versionId, sourcePaths.size());
                return count;
            }
            return 0;
        }
    }

    /** 删除指定版本中 Graphify 导入的子图，并返回删除的节点和关系数量。 */
    public long[] deleteGraphifyClaims(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("projectId", projectId);
            params.put("versionId", normalizeId(versionId));
            params.put("sourceTypes", java.util.List.of("GRAPHIFY_AST", "GRAPHIFY_SEMANTIC"));
            Result result = session.run(
                    """
                    MATCH (n)
                    WHERE n.projectId = $projectId
                      AND (n.versionId = $versionId OR $versionId IS NULL)
                      AND n.sourceType IN $sourceTypes
                    OPTIONAL MATCH (n)-[r]-()
                    WITH collect(DISTINCT n) AS nodes,
                         count(DISTINCT n) AS nodeCount,
                         count(DISTINCT r) AS edgeCount
                    FOREACH (node IN nodes | DETACH DELETE node)
                    RETURN nodeCount, edgeCount
                    """,
                    params);
            if (result.hasNext()) {
                org.neo4j.driver.Record record = result.next();
                long nodeCount = record.get("nodeCount").asLong(0);
                long edgeCount = record.get("edgeCount").asLong(0);
                log.info("Deleted Graphify claims: projectId={}, versionId={}, nodes={}, edges={}",
                        projectId, versionId, nodeCount, edgeCount);
                return new long[]{nodeCount, edgeCount};
            }
            return new long[]{0, 0};
        }
    }
}
