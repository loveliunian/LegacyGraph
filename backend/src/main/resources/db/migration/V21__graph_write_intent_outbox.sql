-- V21__graph_write_intent_outbox.sql
-- 图谱写入意图 outbox 表：持久化写入意图，支持后台执行和重试

CREATE TABLE IF NOT EXISTS lg_graph_write_intent (
    id                VARCHAR(64) PRIMARY KEY,
    project_id        VARCHAR(64)  NOT NULL,
    version_id        VARCHAR(64)  NOT NULL,
    idempotency_key   VARCHAR(256) NOT NULL,
    source            VARCHAR(32)  NOT NULL DEFAULT 'SCAN',
    payload_json      TEXT         NOT NULL,
    status            VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    retry_count       INTEGER      DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at       TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_gwi_idempotency
    ON lg_graph_write_intent (idempotency_key);

CREATE INDEX IF NOT EXISTS idx_gwi_status
    ON lg_graph_write_intent (status, created_at);

CREATE INDEX IF NOT EXISTS idx_gwi_project_version
    ON lg_graph_write_intent (project_id, version_id);
