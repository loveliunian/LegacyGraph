-- V20__source_asset_snapshot.sql
-- 资产快照表：记录每次扫描的 SourceAsset，支持增量扫描判定

CREATE TABLE IF NOT EXISTS lg_source_asset_snapshot (
    id              VARCHAR(64) PRIMARY KEY,
    project_id      VARCHAR(64)  NOT NULL,
    version_id      VARCHAR(64)  NOT NULL,
    repo_id         VARCHAR(64),
    asset_kind      VARCHAR(32)  NOT NULL DEFAULT 'CODE',
    relative_path   TEXT         NOT NULL,
    content_hash    VARCHAR(128),
    file_size       BIGINT       DEFAULT 0,
    last_modified_ms BIGINT      DEFAULT 0,
    extractor_version VARCHAR(128),
    scan_status     VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    previous_snapshot_id VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 唯一索引：同一项目+版本+路径唯一
CREATE UNIQUE INDEX IF NOT EXISTS idx_asset_snapshot_unique
    ON lg_source_asset_snapshot (project_id, version_id, relative_path);

CREATE INDEX IF NOT EXISTS idx_asset_snapshot_status
    ON lg_source_asset_snapshot (project_id, version_id, scan_status);
