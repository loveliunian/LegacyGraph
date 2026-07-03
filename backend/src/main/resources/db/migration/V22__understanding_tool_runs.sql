-- V22: 代码理解工具运行和证据记录表
-- 按《MCP与Zread类工具辅助代码理解和文档生成方案》第6节数据模型落地

-- 工具运行记录表
CREATE TABLE IF NOT EXISTS lg_tool_run (
    id              VARCHAR(36)  PRIMARY KEY,
    project_id      VARCHAR(36)  NOT NULL,
    version_id      VARCHAR(36),
    tool_endpoint_id VARCHAR(36),
    tool_name       VARCHAR(128) NOT NULL,
    tool_kind       VARCHAR(32)  NOT NULL,   -- MCP / CLI / HOSTED_SEARCH / LOCAL
    operation       VARCHAR(64)  NOT NULL,   -- SEARCH_SYMBOL / TRACE_CALL / READ_RESOURCE 等
    query_hash      VARCHAR(64),             -- 查询参数 hash，避免保存过长 prompt
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',  -- PENDING / SUCCESS / FAILED / UNAVAILABLE / TIMEOUT / DENIED
    exit_code       INTEGER,
    elapsed_ms      BIGINT,
    index_freshness VARCHAR(32)  DEFAULT 'UNKNOWN',  -- FRESH / STALE / UNKNOWN
    stdout_sha256   VARCHAR(64),
    stdout_excerpt  TEXT,                     -- 安全截断摘要
    error_excerpt   TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tool_run_project ON lg_tool_run(project_id);
CREATE INDEX IF NOT EXISTS idx_tool_run_tool_name ON lg_tool_run(tool_name);
CREATE INDEX IF NOT EXISTS idx_tool_run_status ON lg_tool_run(status);
CREATE INDEX IF NOT EXISTS idx_tool_run_created ON lg_tool_run(created_at);

-- 工具证据记录表
CREATE TABLE IF NOT EXISTS lg_tool_evidence (
    id              VARCHAR(36)  PRIMARY KEY,
    tool_run_id     VARCHAR(36)  NOT NULL REFERENCES lg_tool_run(id),
    evidence_type   VARCHAR(32)  NOT NULL,   -- SYMBOL / CALL_PATH / SOURCE_SNIPPET / DOC_CHUNK / SUMMARY / REPO_MAP
    source_path     VARCHAR(1024),           -- 文件或文档路径
    symbol_qn       VARCHAR(512),            -- qualified name
    line_start      INTEGER,
    line_end        INTEGER,
    content_sha256  VARCHAR(64),
    excerpt         TEXT,                     -- 安全截断片段
    graph_node_key  VARCHAR(256),            -- 可关联的 LegacyGraph 节点 key
    claim_id        VARCHAR(36),             -- 提升成 Claim 后的关联 ID
    confidence      DOUBLE PRECISION DEFAULT 0.5,
    privacy_level   VARCHAR(32)  DEFAULT 'INTERNAL',  -- PUBLIC / INTERNAL / SENSITIVE
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tool_evidence_run ON lg_tool_evidence(tool_run_id);
CREATE INDEX IF NOT EXISTS idx_tool_evidence_type ON lg_tool_evidence(evidence_type);
CREATE INDEX IF NOT EXISTS idx_tool_evidence_claim ON lg_tool_evidence(claim_id);
CREATE INDEX IF NOT EXISTS idx_tool_evidence_path ON lg_tool_evidence(source_path);
