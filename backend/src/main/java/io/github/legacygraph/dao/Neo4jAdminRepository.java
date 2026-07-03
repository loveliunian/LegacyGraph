package io.github.legacygraph.dao;

import io.github.legacygraph.service.system.CacheService;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
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
}
