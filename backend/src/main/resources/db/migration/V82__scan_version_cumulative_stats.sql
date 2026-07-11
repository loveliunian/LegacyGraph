-- L-14: ScanVersion 累积统计列
-- 记录项目维度全版本累积的节点/边/事实数，用于前端展示项目整体规模
ALTER TABLE lg_scan_version
    ADD COLUMN IF NOT EXISTS cumulative_node_count BIGINT;
ALTER TABLE lg_scan_version
    ADD COLUMN IF NOT EXISTS cumulative_edge_count BIGINT;
ALTER TABLE lg_scan_version
    ADD COLUMN IF NOT EXISTS cumulative_fact_count BIGINT;
ALTER TABLE lg_scan_version
    ADD COLUMN IF NOT EXISTS cumulative_updated_at TIMESTAMP;

COMMENT ON COLUMN lg_scan_version.cumulative_node_count IS 'L-14: 项目维度累积节点数';
COMMENT ON COLUMN lg_scan_version.cumulative_edge_count IS 'L-14: 项目维度累积边数';
COMMENT ON COLUMN lg_scan_version.cumulative_fact_count IS 'L-14: 项目维度累积事实数';
COMMENT ON COLUMN lg_scan_version.cumulative_updated_at IS 'L-14: 累积统计更新时间';
