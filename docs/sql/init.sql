-- ============================================
-- LegacyGraph 数据库初始化脚本
-- 数据库: PostgreSQL 18+
-- ============================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS legacy_graph;
\c legacy_graph;

-- ============================================
-- 1. 项目表
-- ============================================
CREATE TABLE lg_project (
    id              UUID PRIMARY KEY,
    project_code    VARCHAR(128) NOT NULL UNIQUE,
    project_name    VARCHAR(256) NOT NULL,
    description     TEXT,
    project_type    VARCHAR(64) NOT NULL DEFAULT 'LEGACY',
    tech_stack      JSONB,
    repo_url        TEXT,
    default_branch  VARCHAR(128),
    owner           VARCHAR(128),
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE lg_project IS '项目表';

-- ============================================
-- 2. 扫描版本表
-- ============================================
CREATE TABLE lg_scan_version (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_no      VARCHAR(64) NOT NULL,
    branch_name     VARCHAR(128),
    commit_id       VARCHAR(128),
    source_hash     VARCHAR(128),
    scan_scope      JSONB,
    scan_status     VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    error_message   TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_no)
);

COMMENT ON TABLE lg_scan_version IS '项目扫描版本表';

-- ============================================
-- 3. 扫描任务表
-- ============================================
CREATE TABLE lg_scan_task (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
    task_type       VARCHAR(64) NOT NULL,
    task_name       VARCHAR(256) NOT NULL,
    task_status     VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    input_params    JSONB,
    output_summary  JSONB,
    error_message   TEXT,
    retry_count     INT NOT NULL DEFAULT 0,
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_scan_task_project_version ON lg_scan_task(project_id, version_id);
CREATE INDEX idx_lg_scan_task_status ON lg_scan_task(task_status);

COMMENT ON TABLE lg_scan_task IS '扫描任务表';

-- ============================================
-- 4. 原始事实表
-- ============================================
CREATE TABLE lg_fact (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
    fact_type       VARCHAR(128) NOT NULL,
    fact_key        VARCHAR(512) NOT NULL,
    fact_name       VARCHAR(512),
    source_type     VARCHAR(64) NOT NULL,
    source_path     TEXT,
    start_line      INT,
    end_line        INT,
    raw_content     TEXT,
    normalized_data JSONB NOT NULL,
    confidence      NUMERIC(5,4) NOT NULL DEFAULT 1.0000,
    status          VARCHAR(32) NOT NULL DEFAULT 'EXTRACTED',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_id, fact_type, fact_key)
);

CREATE INDEX idx_lg_fact_project_version ON lg_fact(project_id, version_id);
CREATE INDEX idx_lg_fact_type ON lg_fact(fact_type);
CREATE INDEX idx_lg_fact_key ON lg_fact(fact_key);
CREATE INDEX idx_lg_fact_data_gin ON lg_fact USING GIN(normalized_data);

COMMENT ON TABLE lg_fact IS '原始事实表';

-- ============================================
-- 5. 图谱节点表
-- ============================================
CREATE TABLE lg_graph_node (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
    node_type       VARCHAR(128) NOT NULL,
    node_key        VARCHAR(1024) NOT NULL,
    node_name       VARCHAR(512) NOT NULL,
    display_name    VARCHAR(512),
    description     TEXT,
    source_type     VARCHAR(64),
    source_path     TEXT,
    start_line      INT,
    end_line        INT,
    confidence      NUMERIC(5,4) NOT NULL DEFAULT 1.0000,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRM',
    properties      JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_id, node_type, node_key)
);

CREATE INDEX idx_lg_graph_node_project_version ON lg_graph_node(project_id, version_id);
CREATE INDEX idx_lg_graph_node_type ON lg_graph_node(node_type);
CREATE INDEX idx_lg_graph_node_properties ON lg_graph_node USING GIN(properties);

COMMENT ON TABLE lg_graph_node IS '图谱节点表';

-- ============================================
-- 6. 图谱关系表
-- ============================================
CREATE TABLE lg_graph_edge (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
    from_node_id    UUID NOT NULL REFERENCES lg_graph_node(id),
    to_node_id      UUID NOT NULL REFERENCES lg_graph_node(id),
    edge_type       VARCHAR(128) NOT NULL,
    edge_key        VARCHAR(1024) NOT NULL,
    source_type     VARCHAR(64),
    confidence      NUMERIC(5,4) NOT NULL DEFAULT 1.0000,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRM',
    properties      JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_id, edge_key)
);

CREATE INDEX idx_lg_graph_edge_from ON lg_graph_edge(from_node_id);
CREATE INDEX idx_lg_graph_edge_to ON lg_graph_edge(to_node_id);
CREATE INDEX idx_lg_graph_edge_type ON lg_graph_edge(edge_type);

COMMENT ON TABLE lg_graph_edge IS '图谱关系表';

-- ============================================
-- 7. 证据表
-- ============================================
CREATE TABLE lg_evidence (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
    evidence_type   VARCHAR(64) NOT NULL,
    source_path     TEXT,
    start_line      INT,
    end_line        INT,
    content_hash    VARCHAR(128),
    content_excerpt TEXT,
    metadata        JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_evidence_project_version ON lg_evidence(project_id, version_id);

COMMENT ON TABLE lg_evidence IS '证据表';

-- ============================================
-- 8. 节点证据关联表
-- ============================================
CREATE TABLE lg_node_evidence (
    id              UUID PRIMARY KEY,
    node_id         UUID NOT NULL REFERENCES lg_graph_node(id),
    evidence_id     UUID NOT NULL REFERENCES lg_evidence(id),
    relation_type   VARCHAR(64) NOT NULL DEFAULT 'DERIVED_FROM',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(node_id, evidence_id)
);

COMMENT ON TABLE lg_node_evidence IS '节点证据关联表';

-- ============================================
-- 9. 关系证据关联表
-- ============================================
CREATE TABLE lg_edge_evidence (
    id              UUID PRIMARY KEY,
    edge_id         UUID NOT NULL REFERENCES lg_graph_edge(id),
    evidence_id     UUID NOT NULL REFERENCES lg_evidence(id),
    relation_type   VARCHAR(64) NOT NULL DEFAULT 'DERIVED_FROM',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(edge_id, evidence_id)
);

COMMENT ON TABLE lg_edge_evidence IS '关系证据关联表';

-- ============================================
-- 10. 文档片段表
-- ============================================
CREATE TABLE lg_doc_chunk (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
    doc_name        VARCHAR(512) NOT NULL,
    doc_path        TEXT,
    chunk_index     INT NOT NULL,
    title_path      TEXT,
    content         TEXT NOT NULL,
    token_count     INT,
    metadata        JSONB,
    embedding_id    VARCHAR(128),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_doc_chunk_project_version ON lg_doc_chunk(project_id, version_id);

COMMENT ON TABLE lg_doc_chunk IS '文档片段表';

-- ============================================
-- 11. 测试用例表
-- ============================================
CREATE TABLE lg_test_case (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
    case_code       VARCHAR(128) NOT NULL,
    case_name       VARCHAR(512) NOT NULL,
    case_type       VARCHAR(64) NOT NULL,
    target_node_id  UUID REFERENCES lg_graph_node(id),
    priority        VARCHAR(32) NOT NULL DEFAULT 'P2',
    preconditions   JSONB,
    steps           JSONB NOT NULL,
    expected_result JSONB NOT NULL,
    generated_by     VARCHAR(64) NOT NULL,
    confidence      NUMERIC(5,4) NOT NULL DEFAULT 0.8000,
    status          VARCHAR(32) NOT NULL DEFAULT 'GENERATED',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_id, case_code)
);

CREATE INDEX idx_lg_test_case_target ON lg_test_case(target_node_id);

COMMENT ON TABLE lg_test_case IS '测试用例表';

-- ============================================
-- 12. 测试断言表
-- ============================================
CREATE TABLE lg_test_assertion (
    id              UUID PRIMARY KEY,
    test_case_id    UUID NOT NULL REFERENCES lg_test_case(id),
    assertion_type  VARCHAR(64) NOT NULL,
    assertion_name  VARCHAR(512) NOT NULL,
    expression      TEXT NOT NULL,
    expected_value  JSONB,
    actual_value    JSONB,
    status          VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE lg_test_assertion IS '测试断言表';

-- ============================================
-- 13. 测试结果表
-- ============================================
CREATE TABLE lg_test_result (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
    test_case_id    UUID NOT NULL REFERENCES lg_test_case(id),
    execution_id    VARCHAR(128) NOT NULL,
    result_status   VARCHAR(32),
    request_data    JSONB,
    response_data   JSONB,
    db_snapshot     JSONB,
    assertion_result JSONB,
    error_message   TEXT,
    duration_ms     BIGINT,
    executed_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_test_result_case ON lg_test_result(test_case_id);
CREATE INDEX idx_lg_test_result_execution ON lg_test_result(execution_id);

COMMENT ON TABLE lg_test_result IS '测试结果表';

-- ============================================
-- 14. 人工确认表
-- ============================================
CREATE TABLE lg_review_record (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
    target_type     VARCHAR(64) NOT NULL,
    target_id       UUID NOT NULL,
    review_status   VARCHAR(32) NOT NULL,
    reviewer        VARCHAR(128) NOT NULL,
    review_comment  TEXT,
    before_data     JSONB,
    after_data      JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE lg_review_record IS '人工确认记录表';

-- ============================================
-- 创建扩展（如果需要向量检索）
-- ============================================
CREATE EXTENSION IF NOT EXISTS vector;

COMMENT ON EXTENSION vector IS 'pgvector 向量扩展，用于文档和代码片段的向量检索';
