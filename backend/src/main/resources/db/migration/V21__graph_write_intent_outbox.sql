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
-- V22: 扫描版本 AI 增强状态字段
-- 目的：追踪 LLM 语义增强的异步执行状态，解决扫描完成后 AI 增强仍在后台运行的问题
-- 状态值：PENDING（等待执行）/ RUNNING（执行中）/ COMPLETED（已完成）/ FAILED（失败）/ SKIPPED（跳过）

ALTER TABLE lg_scan_version
    ADD COLUMN IF NOT EXISTS ai_enrichment_status VARCHAR(32);

COMMENT ON COLUMN lg_scan_version.ai_enrichment_status IS 'AI 增强状态：PENDING/RUNNING/COMPLETED/FAILED/SKIPPED';
