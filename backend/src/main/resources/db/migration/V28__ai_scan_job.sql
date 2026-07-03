-- V28: AI 扫描异步任务表
CREATE TABLE IF NOT EXISTS lg_ai_scan_job (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    version_id VARCHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    config_json JSONB,
    error_message TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_lg_ai_scan_job_project_version
    ON lg_ai_scan_job(project_id, version_id);
CREATE INDEX IF NOT EXISTS idx_lg_ai_scan_job_status
    ON lg_ai_scan_job(status);
