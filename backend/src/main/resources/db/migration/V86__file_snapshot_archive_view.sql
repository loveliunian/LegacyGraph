-- S1-T1: file_snapshot 只读归档视图
-- 将 lg_file_snapshot 表包装为只读视图，收口 source_snapshot 为唯一落库入口
-- file_snapshot 仍保留用于增量哈希检测（FileChangeDetector 内部使用），
-- 但外部查询统一走 lg_file_snapshot_archive 视图（只读）。

CREATE OR REPLACE VIEW lg_file_snapshot_archive AS
SELECT
    id,
    project_id,
    file_path,
    file_hash,
    file_size,
    scanned_at,
    extractor_version,
    embedding_model,
    graph_ontology_version,
    change_type,
    last_seen_at
FROM lg_file_snapshot;

COMMENT ON VIEW lg_file_snapshot_archive IS 'S1-T1: file_snapshot 只读归档视图 — 外部查询入口，不可写';

-- 禁止通过视图写入（PostgreSQL 视图默认不可写，但显式声明 INSTEAD OF 触发器拒绝写入以明确意图）
-- 简单视图默认可写入，这里通过规则显式拒绝 INSERT/UPDATE/DELETE
CREATE OR REPLACE RULE lg_file_snapshot_archive_no_insert AS
    ON INSERT TO lg_file_snapshot_archive DO INSTEAD NOTHING;
CREATE OR REPLACE RULE lg_file_snapshot_archive_no_update AS
    ON UPDATE TO lg_file_snapshot_archive DO INSTEAD NOTHING;
CREATE OR REPLACE RULE lg_file_snapshot_archive_no_delete AS
    ON DELETE TO lg_file_snapshot_archive DO INSTEAD NOTHING;
