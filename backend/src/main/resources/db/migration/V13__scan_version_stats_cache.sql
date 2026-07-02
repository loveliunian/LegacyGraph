-- ============================================
-- V13: 扫描版本冗余统计字段
-- 目的：把 GET /scan-versions 列表接口的 4×N 次 IO（Neo4j countNodes/countEdges +
--       MySQL scan_task 聚合 + MySQL fact count）降为 0 次 IO（终态版本直接读列）。
-- 语义：仅在扫描完成或失败时由 ProjectScanner 回写；RUNNING/CREATED 状态下允许为 NULL，
--       此时列表接口对该行做一次实时批量聚合兜底。
-- ============================================

ALTER TABLE lg_scan_version
    ADD COLUMN IF NOT EXISTS node_count        BIGINT,
    ADD COLUMN IF NOT EXISTS edge_count        BIGINT,
    ADD COLUMN IF NOT EXISTS fact_count        BIGINT,
    ADD COLUMN IF NOT EXISTS task_total        INT,
    ADD COLUMN IF NOT EXISTS task_success      INT,
    ADD COLUMN IF NOT EXISTS task_failed       INT,
    ADD COLUMN IF NOT EXISTS current_stage     VARCHAR(64),
    ADD COLUMN IF NOT EXISTS stats_updated_at  TIMESTAMP;

COMMENT ON COLUMN lg_scan_version.node_count       IS 'Neo4j 节点数快照（扫描完成时回写）';
COMMENT ON COLUMN lg_scan_version.edge_count       IS 'Neo4j 边数快照（扫描完成时回写）';
COMMENT ON COLUMN lg_scan_version.fact_count       IS 'lg_fact 记录数快照（扫描完成时回写）';
COMMENT ON COLUMN lg_scan_version.task_total       IS '子任务总数快照';
COMMENT ON COLUMN lg_scan_version.task_success     IS '成功子任务数快照';
COMMENT ON COLUMN lg_scan_version.task_failed      IS '失败子任务数快照';
COMMENT ON COLUMN lg_scan_version.current_stage    IS '最后阶段快照（COMPLETED / 首个非 SUCCESS 子任务 taskType）';
COMMENT ON COLUMN lg_scan_version.stats_updated_at IS '统计字段最近一次回写时间';
