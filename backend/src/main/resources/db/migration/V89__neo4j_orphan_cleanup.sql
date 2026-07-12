-- S3-T1: Neo4j 孤儿节点清理脚本
-- 配合 scan_version + node_key 复合唯一约束的新增，清理历史重复/孤儿数据
-- 注意：Neo4j 约束和清理逻辑在 Java 侧（Neo4jGraphDao / Neo4jSchemaRepository）执行，
-- 本脚本仅记录 PostgreSQL 侧的对账辅助表，用于跟踪清理执行状态。
--
-- H21: 原文件名 V85__neo4j_orphan_cleanup.sql 与 V85__graph_release_metrics.sql 版本号冲突，
--      Flyway 会报 "Found non-empty schema(s) with the same migration version 85"。
--      本文件重命名为 V89 以消除冲突。

CREATE TABLE IF NOT EXISTS lg_neo4j_orphan_cleanup_log (
    id BIGSERIAL PRIMARY KEY,
    project_id VARCHAR(64),
    version_id VARCHAR(64),
    cleanup_type VARCHAR(32) NOT NULL,
    nodes_removed INT DEFAULT 0,
    edges_removed INT DEFAULT 0,
    duplicates_merged INT DEFAULT 0,
    executed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes TEXT
);

COMMENT ON TABLE lg_neo4j_orphan_cleanup_log IS 'S3-T1: Neo4j 孤儿/重复节点清理执行日志';
COMMENT ON COLUMN lg_neo4j_orphan_cleanup_log.cleanup_type IS '清理类型：ORPHAN_NODE / DANGLING_EDGE / DUPLICATE_MERGE';
