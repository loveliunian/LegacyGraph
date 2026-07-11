-- ============================================
-- V75: 图节点 tombstone / stale 状态支持（G-05 增量扫描补齐删除/重命名/逻辑重扫）
--
-- lg_graph_node 表已有 source_path、status、deleted、version_id、last_seen_at 字段，
-- 本迁移补充 tombstone 相关辅助字段，用于记录失效原因与标记时间。
--
-- 状态值约定（写入 status 列）：
--   TOMBSTONED  — 文件已被删除，对应节点标记为墓碑（软删除前过渡态）
--   STALE       — 需要逻辑重扫（依赖变更、引用方变更等）
--   EVICTED     — 已无最新引用，向量与节点失效
--   PENDING_CONFIRM / CONFIRMED — 原有状态
-- ============================================

-- tombstone 原因：记录节点被标记为 TOMBSTONED/EVICTED/STALE 的原因
ALTER TABLE lg_graph_node ADD COLUMN IF NOT EXISTS tombstone_reason VARCHAR(256);

-- tombstone 标记时间：记录节点被标记为 TOMBSTONED/EVICTED/STALE 的时间
ALTER TABLE lg_graph_node ADD COLUMN IF NOT EXISTS tombstoned_at TIMESTAMP;

-- 最后确认存在的扫描版本 ID：用于 evict 判定（节点在哪个扫描版本中仍被引用）
ALTER TABLE lg_graph_node ADD COLUMN IF NOT EXISTS last_scan_version_id VARCHAR(40);

-- 源索引：按 source_path 加速删除/重命名检测查询
CREATE INDEX IF NOT EXISTS idx_lg_graph_node_source_path
    ON lg_graph_node(source_path);
CREATE INDEX IF NOT EXISTS idx_lg_graph_node_status
    ON lg_graph_node(project_id, status);

COMMENT ON COLUMN lg_graph_node.tombstone_reason IS '墓碑/失效/过期原因（G-05）';
COMMENT ON COLUMN lg_graph_node.tombstoned_at IS '标记为墓碑/失效/过期的时间（G-05）';
COMMENT ON COLUMN lg_graph_node.last_scan_version_id IS '最后确认存在的扫描版本 ID（G-05 evict 判定）';
