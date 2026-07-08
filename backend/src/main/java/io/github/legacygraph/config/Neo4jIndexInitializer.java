package io.github.legacygraph.config;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Neo4j 索引初始化器 — 异步创建性能必需的复合索引，不阻塞启动。
 */
@Slf4j
@Component
public class Neo4jIndexInitializer {

    private final Driver neo4jDriver;

    public Neo4jIndexInitializer(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void createIndexes() {
        log.info("Starting Neo4j index initialization...");
        List<String> statements = List.of(
                // 节点：按项目+版本+类型查询（图谱窗口查询 / 全量查询热点）
                "CREATE INDEX node_project_version_type IF NOT EXISTS "
                        + "FOR (n:Node) ON (n.projectId, n.versionId, n.nodeType)",
                // 节点：按项目+版本+nodeKey 精确查找
                "CREATE INDEX node_project_version_key IF NOT EXISTS "
                        + "FOR (n:Node) ON (n.projectId, n.versionId, n.nodeKey)",
                // 边：按项目+版本+边类型查询
                "CREATE INDEX edge_project_version_type IF NOT EXISTS "
                        + "FOR ()-[e:Edge]-() ON (e.projectId, e.versionId, e.edgeType)"
        );

        try (Session session = neo4jDriver.session()) {
            for (String stmt : statements) {
                try {
                    session.run(stmt);
                    log.debug("Neo4j index ensured: {}", stmt);
                } catch (Exception e) {
                    log.warn("Failed to create Neo4j index (may already exist): {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Neo4j index initialization failed — graph queries may be slow. "
                    + "Ensure Neo4j is running and indexes are created manually.", e);
            return;
        }
        log.info("Neo4j index initialization completed ({} indexes ensured).", statements.size());
    }
}
