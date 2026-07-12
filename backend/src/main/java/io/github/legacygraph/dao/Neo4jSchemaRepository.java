package io.github.legacygraph.dao;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

/**
 * Neo4j Schema Repository — 负责索引和约束的创建与管理。
 */
@Slf4j
@Component
public class Neo4jSchemaRepository {

    private final Driver neo4jDriver;

    public Neo4jSchemaRepository(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    /**
     * 创建约束（服务启动时初始化）。覆盖 NodeType 全部类型，避免新增类型遗漏索引导致全标签扫描。
     * L-07: 新增 (projectId, versionId, nodeKey) 复合唯一约束，杜绝跨 adapter 重复节点。
     */
    public void createConstraints() {
        try (Session session = neo4jDriver.session()) {
            for (NodeType type : NodeType.values()) {
                String label = CypherCatalog.safeIdentifier(type.name(), "nodeType");
                // 基础：id 唯一约束
                session.run(String.format(
                        "CREATE CONSTRAINT %s_id_key IF NOT EXISTS FOR (n:%s) REQUIRE n.id IS UNIQUE",
                        label.toLowerCase(), label));
                // L-07: 复合唯一约束 (projectId, versionId, nodeKey)
                session.run(String.format(
                        "CREATE CONSTRAINT %s_composite_key IF NOT EXISTS FOR (n:%s) REQUIRE (n.projectId, n.versionId, n.nodeKey) IS UNIQUE",
                        label.toLowerCase(), label));
            }
            log.info("Created Neo4j constraints (id UNIQUE + composite key UNIQUE) for {} node types", NodeType.values().length);
        }
    }

    /** 创建索引（Neo4j 5.x 要求 FOR (n:Label) 语法）。覆盖 NodeType 全部类型。 */
    public void createIndexes() {
        try (Session session = neo4jDriver.session()) {
            for (NodeType type : NodeType.values()) {
                String label = CypherCatalog.safeIdentifier(type.name(), "nodeType");
                // 复合索引：加速按 projectId + versionId 查询
                session.run(String.format(
                        "CREATE INDEX IF NOT EXISTS FOR (n:%s) ON (n.projectId, n.versionId)", label));
                // nodeKey 索引：加速按 nodeKey 点查（MERGE/去重的关键索引）
                session.run(String.format(
                        "CREATE INDEX IF NOT EXISTS FOR (n:%s) ON (n.nodeKey)", label));
                // S3-T7: nodeName 索引 — 中文字段名加速精确匹配（getTableImpact 等按名称查询）
                session.run(String.format(
                        "CREATE INDEX IF NOT EXISTS FOR (n:%s) ON (n.nodeName)", label));
                // S3-T7: sourcePath 索引 — 加速 findNodesBySourcePath 查询
                session.run(String.format(
                        "CREATE INDEX IF NOT EXISTS FOR (n:%s) ON (n.sourcePath)", label));
            }

            // Relationship 属性索引：加速按 projectId + versionId 查询边
            for (EdgeType type : EdgeType.values()) {
                String relType = CypherCatalog.safeIdentifier(type.name(), "edgeType");
                session.run(String.format(
                        "CREATE INDEX IF NOT EXISTS FOR ()-[r:%s]-() ON (r.projectId, r.versionId)", relType));
            }
            log.info("Created Neo4j indexes for {} node types and {} edge types (incl. S3-T7 nodeName/sourcePath indexes)",
                    NodeType.values().length, EdgeType.values().length);
            // id 属性已在 createConstraints 中通过 UNIQUE 约束自动索引，无需重复建
        }
    }

    /**
     * 统一入口：创建所有必要的索引和约束。
     * 组合 createConstraints + createIndexes。
     */
    public void ensureIndexesAndConstraints() {
        createConstraints();
        createIndexes();
    }
}
