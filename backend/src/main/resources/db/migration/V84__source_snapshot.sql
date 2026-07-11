-- G-02: SourceSnapshot 不可变快照表
-- 记录每次扫描时资料源的不可变状态快照，用于追溯"扫描时看到了什么"。
-- 与 lg_file_snapshot（记录文件哈希用于增量检测）不同，本表记录资料源级别的元信息快照。
CREATE TABLE IF NOT EXISTS lg_source_snapshot (
    id BIGSERIAL PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    descriptor_json TEXT NOT NULL,
    content_hash VARCHAR(64),
    content_size BIGINT,
    snapshot_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 按项目+版本查询
CREATE INDEX IF NOT EXISTS idx_source_snapshot_project_version
    ON lg_source_snapshot (project_id, version_id);
-- 按项目+版本+类型查询
CREATE INDEX IF NOT EXISTS idx_source_snapshot_type
    ON lg_source_snapshot (project_id, version_id, source_type);

COMMENT ON TABLE lg_source_snapshot IS 'G-02: 资料源不可变快照 — 记录扫描时资料源状态';
COMMENT ON COLUMN lg_source_snapshot.descriptor_json IS 'SourceDescriptor 序列化 JSON（完整元信息）';
