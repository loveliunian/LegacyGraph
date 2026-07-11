-- L-06: 扫描检查点表 — 支持 pause/resume 后从断点恢复
CREATE TABLE IF NOT EXISTS lg_scan_checkpoint (
    id VARCHAR(64) PRIMARY KEY,
    version_id VARCHAR(64) NOT NULL,
    phase VARCHAR(64) NOT NULL,
    last_file_index INT,
    last_file_path VARCHAR(1024),
    processed_files INT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_scan_checkpoint_version_phase ON lg_scan_checkpoint(version_id, phase);

COMMENT ON TABLE lg_scan_checkpoint IS 'L-06: 扫描检查点，支持 pause/resume 从断点恢复';
