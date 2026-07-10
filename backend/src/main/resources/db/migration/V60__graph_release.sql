-- V60__graph_release.sql
-- 图谱发布表：记录图谱发布生命周期，支持状态机 DRAFT → VALIDATING → PUBLISHED | FAILED
-- 通过 (project_id, scan_version_id) 唯一索引保证幂等性

CREATE TABLE IF NOT EXISTS lg_graph_release (
    id                  VARCHAR(64)   PRIMARY KEY,
    project_id          VARCHAR(64)   NOT NULL,
    scan_version_id     VARCHAR(64)   NOT NULL,
    graph_version_tag   VARCHAR(128),
    status              VARCHAR(32)   NOT NULL DEFAULT 'DRAFT',
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at        TIMESTAMP,
    failure_reasons     JSONB
);

COMMENT ON TABLE  lg_graph_release IS '图谱发布记录表';
COMMENT ON COLUMN lg_graph_release.status IS '状态：DRAFT / VALIDATING / PUBLISHED / FAILED';
COMMENT ON COLUMN lg_graph_release.failure_reasons IS '失败原因列表（JSONB 数组）';

-- 唯一索引：同 project+version 只能有一条发布记录（幂等保证）
CREATE UNIQUE INDEX IF NOT EXISTS idx_graph_release_project_version
    ON lg_graph_release (project_id, scan_version_id);

-- 状态查询索引
CREATE INDEX IF NOT EXISTS idx_graph_release_status
    ON lg_graph_release (status);
