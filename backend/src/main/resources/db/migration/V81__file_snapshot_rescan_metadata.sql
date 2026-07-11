-- L-05: 文件快照表增加增量重扫元数据列
-- 用于 LOGIC_RESCAN 检测：当 extractor/embedding/ontology 版本变化时触发逻辑重扫
ALTER TABLE lg_file_snapshot
    ADD COLUMN IF NOT EXISTS extractor_version VARCHAR(64);
ALTER TABLE lg_file_snapshot
    ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(128);
ALTER TABLE lg_file_snapshot
    ADD COLUMN IF NOT EXISTS graph_ontology_version VARCHAR(64);
ALTER TABLE lg_file_snapshot
    ADD COLUMN IF NOT EXISTS change_type VARCHAR(32);
ALTER TABLE lg_file_snapshot
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;

COMMENT ON COLUMN lg_file_snapshot.extractor_version IS 'L-05: 抽取器版本（变化时触发 LOGIC_RESCAN）';
COMMENT ON COLUMN lg_file_snapshot.embedding_model IS 'L-05: 嵌入模型名称（变化时触发 LOGIC_RESCAN）';
COMMENT ON COLUMN lg_file_snapshot.graph_ontology_version IS 'L-05: 图谱本体版本（变化时触发 LOGIC_RESCAN）';
COMMENT ON COLUMN lg_file_snapshot.change_type IS 'L-05: 变更类型 ADDED/MODIFIED/DELETED/RENAMED/LOGIC_RESCAN';
COMMENT ON COLUMN lg_file_snapshot.last_seen_at IS 'L-05: 最后可见时间（RENAMED 后旧节点清退用）';
