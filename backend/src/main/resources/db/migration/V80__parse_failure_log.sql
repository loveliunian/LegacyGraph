-- L-03: 文档分片解析失败日志表
-- 记录大文档分片解析失败的详细信息，用于后续重试和治理
CREATE TABLE IF NOT EXISTS lg_parse_failure (
    id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    shard_index INT NOT NULL,
    shard_total INT NOT NULL,
    char_start INT,
    char_end INT,
    failure_type VARCHAR(64) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_parse_failure_project_version ON lg_parse_failure(project_id, version_id);
CREATE INDEX IF NOT EXISTS idx_parse_failure_document ON lg_parse_failure(document_id);

COMMENT ON TABLE lg_parse_failure IS 'L-03: 文档分片解析失败日志';
COMMENT ON COLUMN lg_parse_failure.shard_index IS '分片序号（从0开始）';
COMMENT ON COLUMN lg_parse_failure.shard_total IS '总分片数';
COMMENT ON COLUMN lg_parse_failure.failure_type IS '失败类型：OOM/LLM_ERROR/READ_ERROR/EMPTY_CONTENT/SHARD_ERROR/OTHER';
