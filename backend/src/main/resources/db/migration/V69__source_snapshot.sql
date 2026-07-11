CREATE TABLE IF NOT EXISTS lg_source_snapshot (
    id                  VARCHAR(40) PRIMARY KEY,
    project_id          VARCHAR(64) NOT NULL,
    source_type         VARCHAR(20) NOT NULL,
    source_id           VARCHAR(64) NOT NULL,
    source_uri          VARCHAR(512),
    content_hash        VARCHAR(64) NOT NULL,
    parent_snapshot_id  VARCHAR(40),
    scan_version_id     VARCHAR(40),
    mime_type           VARCHAR(64),
    size_bytes          BIGINT,
    acl_hash            VARCHAR(64),
    storage_uri         VARCHAR(512),
    status              VARCHAR(16) NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_snapshot_project ON lg_source_snapshot(project_id, source_type);
CREATE INDEX IF NOT EXISTS idx_snapshot_version ON lg_source_snapshot(scan_version_id);
