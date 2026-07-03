-- ============================================
-- LegacyGraph H2 测试数据库初始化脚本
-- 从 PostgreSQL init.sql 转换，适配 H2 方言
-- 转换说明：
--   UUID → VARCHAR(36)
--   JSONB → TEXT
--   BIGSERIAL → BIGINT AUTO_INCREMENT
--   去掉 COMMENT ON
--   去掉 pgvector/GIN/HNSW 索引
-- ============================================

DROP ALL OBJECTS;

-- ============================================
-- 1. 项目表
-- ============================================
CREATE TABLE lg_project (
    id              VARCHAR(36) PRIMARY KEY,
    project_code    VARCHAR(128) NOT NULL UNIQUE,
    project_name    VARCHAR(256) NOT NULL,
    description     TEXT,
    project_type    VARCHAR(64) NOT NULL DEFAULT 'LEGACY',
    tech_stack      TEXT,
    repo_url        TEXT,
    default_branch  VARCHAR(128),
    owner           VARCHAR(128),
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Disable referential integrity for tests (FK constraints cause issues with 
-- soft-delete + unique constraint combos in H2)
SET REFERENTIAL_INTEGRITY FALSE;

-- ============================================
-- 2. 代码仓库表
-- ============================================
CREATE TABLE lg_code_repo (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    repo_name       VARCHAR(256) NOT NULL,
    repo_type       VARCHAR(32) NOT NULL DEFAULT 'GIT',
    git_url         TEXT NOT NULL,
    branch_name     VARCHAR(128),
    auth_type       VARCHAR(32) DEFAULT 'NONE',
    username        VARCHAR(128),
    include_pattern TEXT,
    exclude_pattern TEXT,
    local_path      TEXT,
    backend_sub_path  TEXT,
    frontend_sub_path TEXT,
    status          VARCHAR(32) NOT NULL DEFAULT 'NEW',
    last_pull_status VARCHAR(32),
    last_pull_time  TIMESTAMP,
    last_scan_time  TIMESTAMP,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(128),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_code_repo_project ON lg_code_repo(project_id);

-- ============================================
-- 3. 数据库连接表
-- ============================================
CREATE TABLE lg_db_connection (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    connection_name VARCHAR(128) NOT NULL,
    db_type         VARCHAR(32) NOT NULL,
    host            VARCHAR(128) NOT NULL,
    port            INT NOT NULL,
    database_name   VARCHAR(128) NOT NULL,
    schema_name     VARCHAR(128),
    username        VARCHAR(128),
    password        VARCHAR(256),
    readonly        BOOLEAN DEFAULT FALSE,
    include_tables  TEXT,
    exclude_tables  TEXT,
    status          VARCHAR(32) NOT NULL DEFAULT 'NEW',
    table_count     INT,
    last_scan_time  TIMESTAMP,
    schema_fingerprint VARCHAR(128),
    schema_fingerprint_updated_at TIMESTAMP,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(128),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_db_connection_project ON lg_db_connection(project_id);

-- ============================================
-- 4. 上传文档表
-- ============================================
CREATE TABLE lg_document (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_id      VARCHAR(36),
    doc_name        VARCHAR(512) NOT NULL,
    doc_type        VARCHAR(64),
    file_type       VARCHAR(32),
    file_path       TEXT,
    file_size       BIGINT,
    parse_status    VARCHAR(32) DEFAULT 'PENDING',
    fact_count      INT DEFAULT 0,
    error_message   TEXT,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    uploaded_by     VARCHAR(128),
    uploaded_at     TIMESTAMP,
    parsed_at       TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_document_project ON lg_document(project_id);

-- ============================================
-- 5. 扫描版本表
-- ============================================
CREATE TABLE lg_scan_version (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_no      VARCHAR(64) NOT NULL,
    branch_name     VARCHAR(128),
    commit_id       VARCHAR(128),
    source_hash     VARCHAR(128),
    scan_scope      TEXT,
    scan_status     VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    error_message   TEXT,
    node_count        BIGINT,
    edge_count        BIGINT,
    fact_count        BIGINT,
    task_total        INT,
    task_success      INT,
    task_failed       INT,
    current_stage     VARCHAR(64),
    stats_updated_at  TIMESTAMP,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_no)
);

CREATE INDEX idx_lg_scan_version_project ON lg_scan_version(project_id);

-- ============================================
-- 6. 扫描任务表
-- ============================================
CREATE TABLE lg_scan_task (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_id      VARCHAR(36) NOT NULL REFERENCES lg_scan_version(id),
    task_type       VARCHAR(64) NOT NULL,
    task_name       VARCHAR(256) NOT NULL,
    task_status     VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    input_params    TEXT,
    output_summary  TEXT,
    error_message   TEXT,
    retry_count     INT NOT NULL DEFAULT 0,
    total_items     INT,
    processed_items INT DEFAULT 0,
    current_item    VARCHAR(1024),
    deleted         SMALLINT NOT NULL DEFAULT 0,
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_scan_task_project_version ON lg_scan_task(project_id, version_id);
CREATE INDEX idx_lg_scan_task_status ON lg_scan_task(task_status);

-- ============================================
-- 6a. AI 扫描异步任务表
-- ============================================
CREATE TABLE lg_ai_scan_job (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL,
    version_id      VARCHAR(36) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    config_json     TEXT,
    error_message   TEXT,
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_ai_scan_job_project_version ON lg_ai_scan_job(project_id, version_id);
CREATE INDEX idx_lg_ai_scan_job_status ON lg_ai_scan_job(status);

-- ============================================
-- 7. 原始事实表
-- ============================================
CREATE TABLE lg_fact (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_id      VARCHAR(36) NOT NULL REFERENCES lg_scan_version(id),
    fact_type       VARCHAR(128) NOT NULL,
    fact_key        VARCHAR(512) NOT NULL,
    fact_name       VARCHAR(512),
    source_type     VARCHAR(64) NOT NULL,
    source_path     TEXT,
    start_line      INT,
    end_line        INT,
    source_line     INT,
    raw_content     TEXT,
    content_summary TEXT,
    normalized_data TEXT NOT NULL,
    confidence      DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
    status          VARCHAR(32) NOT NULL DEFAULT 'EXTRACTED',
    mapped_to_graph BOOLEAN,
    related_node_count INT,
    created_by      VARCHAR(128),
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_id, fact_type, fact_key)
);

CREATE INDEX idx_lg_fact_project_version ON lg_fact(project_id, version_id);
CREATE INDEX idx_lg_fact_type ON lg_fact(fact_type);
CREATE INDEX idx_lg_fact_key ON lg_fact(fact_key);

-- ============================================
-- 8. 图谱节点表
-- ============================================
CREATE TABLE lg_graph_node (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_id      VARCHAR(36) NOT NULL REFERENCES lg_scan_version(id),
    node_type       VARCHAR(128) NOT NULL,
    node_key        VARCHAR(1024) NOT NULL,
    node_name       VARCHAR(512) NOT NULL,
    display_name    VARCHAR(512),
    description     TEXT,
    source_type     VARCHAR(64),
    source_path     TEXT,
    start_line      INT,
    end_line        INT,
    confidence      DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRM',
    properties      TEXT,
    alias_names     TEXT,
    evidence_ids    TEXT,
    semantic_vector_ref BIGINT,
    verified_score  DECIMAL(5,4) DEFAULT 0.0000,
    runtime_verified BOOLEAN DEFAULT FALSE,
    last_seen_at    TIMESTAMP,
    trace_count     INT DEFAULT 0,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_id, node_type, node_key)
);

CREATE INDEX idx_lg_graph_node_project_version ON lg_graph_node(project_id, version_id);
CREATE INDEX idx_lg_graph_node_type ON lg_graph_node(node_type);

-- ============================================
-- 9. 图谱关系表
-- ============================================
CREATE TABLE lg_graph_edge (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_id      VARCHAR(36) NOT NULL REFERENCES lg_scan_version(id),
    from_node_id    VARCHAR(36) NOT NULL REFERENCES lg_graph_node(id),
    to_node_id      VARCHAR(36) NOT NULL REFERENCES lg_graph_node(id),
    edge_type       VARCHAR(128) NOT NULL,
    edge_key        VARCHAR(1024) NOT NULL,
    source_type     VARCHAR(64),
    confidence      DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRM',
    properties      TEXT,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    evidence_ids    TEXT,
    verified_score  DECIMAL(5,4) DEFAULT 0.0000,
    relation_status VARCHAR(32),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_id, edge_key)
);

CREATE INDEX idx_lg_graph_edge_from ON lg_graph_edge(from_node_id);
CREATE INDEX idx_lg_graph_edge_to ON lg_graph_edge(to_node_id);
CREATE INDEX idx_lg_graph_edge_type ON lg_graph_edge(edge_type);

-- ============================================
-- 10. 证据表
-- ============================================
CREATE TABLE lg_evidence (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_id      VARCHAR(36) NOT NULL REFERENCES lg_scan_version(id),
    evidence_type   VARCHAR(64) NOT NULL,
    source_path     TEXT,
    source_name     VARCHAR(512),
    start_line      INT,
    end_line        INT,
    content_hash    VARCHAR(128),
    content_excerpt TEXT,
    summary         TEXT,
    content         TEXT,
    metadata        TEXT,
    ast_path        TEXT,
    sql_hash        VARCHAR(128),
    chunk_id        BIGINT,
    related_node_ids TEXT,
    privacy_level   VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    redaction_policy VARCHAR(20) NOT NULL DEFAULT 'none',
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_evidence_project_version ON lg_evidence(project_id, version_id);

-- ============================================
-- 11. 节点证据关联表
-- ============================================
CREATE TABLE lg_node_evidence (
    id              VARCHAR(36) PRIMARY KEY,
    node_id         VARCHAR(36) NOT NULL REFERENCES lg_graph_node(id),
    evidence_id     VARCHAR(36) NOT NULL REFERENCES lg_evidence(id),
    relation_type   VARCHAR(64) NOT NULL DEFAULT 'DERIVED_FROM',
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(node_id, evidence_id)
);

-- ============================================
-- 12. 关系证据关联表
-- ============================================
CREATE TABLE lg_edge_evidence (
    id              VARCHAR(36) PRIMARY KEY,
    edge_id         VARCHAR(36) NOT NULL REFERENCES lg_graph_edge(id),
    evidence_id     VARCHAR(36) NOT NULL REFERENCES lg_evidence(id),
    relation_type   VARCHAR(64) NOT NULL DEFAULT 'DERIVED_FROM',
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(edge_id, evidence_id)
);

-- ============================================
-- 13. 文档片段表
-- ============================================
CREATE TABLE lg_doc_chunk (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_id      VARCHAR(36) NOT NULL REFERENCES lg_scan_version(id),
    doc_name        VARCHAR(512) NOT NULL,
    doc_path        TEXT,
    chunk_index     INT NOT NULL,
    title_path      TEXT,
    content         TEXT NOT NULL,
    token_count     INT,
    metadata        TEXT,
    embedding_id    VARCHAR(128),
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_doc_chunk_project_version ON lg_doc_chunk(project_id, version_id);

-- ============================================
-- 14. 向量文档表（H2 不支持 vector 类型，改为 TEXT 占位）
-- ============================================
CREATE TABLE vector_document (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    content         TEXT NOT NULL,
    embedding       TEXT,
    metadata        TEXT,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 14b. lg_vector_document（用于 VectorDocument 实体）
CREATE TABLE lg_vector_document (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    project_id      VARCHAR(64) NOT NULL,
    version_id      VARCHAR(64) NOT NULL,
    chunk_type      VARCHAR(64) NOT NULL DEFAULT 'DOC',
    source_uri      TEXT,
    source_hash     VARCHAR(128),
    chunk_index     INT,
    content         TEXT NOT NULL,
    content_sha256  VARCHAR(128),
    meta            TEXT DEFAULT '{}',
    embedding       TEXT,
    embedding_model VARCHAR(128),
    embedding_dim   INT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_vector_doc_project_version
    ON lg_vector_document(project_id, version_id);
CREATE INDEX idx_vector_doc_chunk_type
    ON lg_vector_document(chunk_type);

-- ============================================
-- 15. 测试用例表
-- ============================================
CREATE TABLE lg_test_case (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_id      VARCHAR(36) NOT NULL REFERENCES lg_scan_version(id),
    case_code       VARCHAR(128) NOT NULL,
    case_name       VARCHAR(512) NOT NULL,
    case_type       VARCHAR(64) NOT NULL,
    scenario        VARCHAR(64),
    target_node_id  VARCHAR(36) REFERENCES lg_graph_node(id),
    priority        VARCHAR(32) NOT NULL DEFAULT 'P2',
    preconditions   TEXT,
    steps           TEXT NOT NULL,
    expected_result TEXT NOT NULL,
    generated_by    VARCHAR(64) NOT NULL,
    confidence      DECIMAL(5,4) NOT NULL DEFAULT 0.8000,
    status          VARCHAR(32) NOT NULL DEFAULT 'GENERATED',
    deleted         INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_id, case_code)
);

CREATE INDEX idx_lg_test_case_target ON lg_test_case(target_node_id);

-- ============================================
-- 16. 测试断言表
-- ============================================
CREATE TABLE lg_test_assertion (
    id              VARCHAR(36) PRIMARY KEY,
    test_case_id    VARCHAR(36) NOT NULL REFERENCES lg_test_case(id),
    assertion_type  VARCHAR(64) NOT NULL,
    assertion_name  VARCHAR(512) NOT NULL,
    expression      TEXT NOT NULL,
    expected_value  TEXT,
    actual_value    TEXT,
    status          VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Disable referential integrity for tests (FK constraints cause issues with 
-- soft-delete + unique constraint combos in H2)
SET REFERENTIAL_INTEGRITY FALSE;

-- ============================================
-- 17. 测试结果表
-- ============================================
CREATE TABLE lg_test_result (
    id               VARCHAR(36) PRIMARY KEY,
    project_id       VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_id       VARCHAR(36) NOT NULL REFERENCES lg_scan_version(id),
    test_case_id     VARCHAR(36) NOT NULL REFERENCES lg_test_case(id),
    execution_id     VARCHAR(128) NOT NULL,
    result_status    VARCHAR(32),
    request_data     TEXT,
    response_data    TEXT,
    db_snapshot      TEXT,
    assertion_result TEXT,
    error_message    TEXT,
    duration_ms      BIGINT,
    deleted          SMALLINT NOT NULL DEFAULT 0,
    executed_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_test_result_case ON lg_test_result(test_case_id);
CREATE INDEX idx_lg_test_result_execution ON lg_test_result(execution_id);

-- ============================================
-- 18. 测试运行表
-- ============================================
CREATE TABLE lg_test_run (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_id      VARCHAR(36) NOT NULL REFERENCES lg_scan_version(id),
    environment     VARCHAR(32),
    status          VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    total_cases     INT,
    passed_cases    INT,
    failed_cases    INT,
    deleted         INT DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_test_run_project ON lg_test_run(project_id);
CREATE INDEX idx_lg_test_run_status ON lg_test_run(status);

-- ============================================
-- 19. 人工确认记录表
-- ============================================
CREATE TABLE lg_review_record (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_id      VARCHAR(36) NOT NULL REFERENCES lg_scan_version(id),
    target_type     VARCHAR(64) NOT NULL,
    target_id       VARCHAR(36) NOT NULL,
    target_name     VARCHAR(256),
    graph_type      VARCHAR(64),
    confidence      DECIMAL(5,4),
    evidence_count  INT,
    priority        VARCHAR(32),
    status          VARCHAR(32),
    assignee        VARCHAR(128),
    comment         TEXT,
    reviewed_by     VARCHAR(128),
    reviewed_at     TIMESTAMP,
    before_data     TEXT,
    after_data      TEXT,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 20. 迁移风险表
-- ============================================
CREATE TABLE migration_risk (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_id      VARCHAR(36) NOT NULL REFERENCES lg_scan_version(id),
    risk_type       VARCHAR(64) NOT NULL,
    risk_name       VARCHAR(256) NOT NULL,
    description     TEXT,
    affected_nodes  TEXT,
    severity        VARCHAR(32) NOT NULL DEFAULT 'MEDIUM',
    status          VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    mitigation      TEXT,
    estimated_effort INT,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Disable referential integrity for tests (FK constraints cause issues with 
-- soft-delete + unique constraint combos in H2)
SET REFERENTIAL_INTEGRITY FALSE;

CREATE INDEX idx_migration_risk_project ON migration_risk(project_id);
CREATE INDEX idx_migration_risk_severity ON migration_risk(severity);
CREATE INDEX idx_migration_risk_status ON migration_risk(status);

-- ============================================
-- 21. LLM 配置表（适配 @TableName("lg_llm_provider") 实体）
-- ============================================
CREATE TABLE lg_llm_provider (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    provider_code    VARCHAR(128),
    model_id         VARCHAR(128),
    endpoint         TEXT,
    deployment_mode  VARCHAR(64),
    api_config       TEXT,
    is_default       BOOLEAN DEFAULT FALSE,
    is_active        BOOLEAN DEFAULT TRUE,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 22. 提示词模板表（适配 @TableName("lg_prompt_template") 实体）
-- ============================================
CREATE TABLE lg_prompt_template (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    template_code    VARCHAR(64),
    version          VARCHAR(32),
    scene            VARCHAR(64),
    system_prompt    TEXT,
    domain_prompt    TEXT,
    task_prompt      TEXT,
    output_schema    TEXT,
    is_active        BOOLEAN DEFAULT TRUE,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 23. LLM 调用记录表（适配 @TableName("lg_prompt_run") 实体）
-- ============================================
CREATE TABLE lg_prompt_run (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    project_id       VARCHAR(36) REFERENCES lg_project(id),
    task_type        VARCHAR(64),
    provider_code    VARCHAR(128),
    model_id         VARCHAR(128),
    template_code    VARCHAR(64),
    template_version VARCHAR(32),
    input_hash       VARCHAR(128),
    masked_input     TEXT,
    raw_output       TEXT,
    parsed_output    TEXT,
    prompt_tokens    INT,
    completion_tokens INT,
    latency_ms       BIGINT,
    status           VARCHAR(32),
    created_by       VARCHAR(128),
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_prompt_run_project ON lg_prompt_run(project_id);
CREATE INDEX idx_lg_prompt_run_created ON lg_prompt_run(created_at);

-- ============================================
-- 24. 报告表
-- ============================================
CREATE TABLE report (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_id      VARCHAR(36) NOT NULL REFERENCES lg_scan_version(id),
    report_type     VARCHAR(64) NOT NULL,
    report_name     VARCHAR(256) NOT NULL,
    report_data     TEXT,
    file_url        TEXT,
    generated_by    VARCHAR(128),
    status          VARCHAR(32) NOT NULL DEFAULT 'GENERATING',
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMP
);

-- 24b. 报告记录表（lg_reports - 用于 Report 实体）
CREATE TABLE lg_reports (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES lg_project(id),
    version_id      VARCHAR(36),
    report_type     VARCHAR(64) NOT NULL,
    report_name     VARCHAR(256) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'GENERATING',
    file_path       TEXT,
    error_message   TEXT,
    generated_at    TIMESTAMP,
    completed_at    TIMESTAMP,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 25. 系统操作日志表 (审计日志)
-- ============================================
CREATE TABLE sys_operation_log (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    trace_id        VARCHAR(128),
    operation       VARCHAR(128) NOT NULL,
    method          VARCHAR(256),
    request_uri     TEXT,
    request_method  VARCHAR(16),
    client_ip       VARCHAR(64),
    user_agent      TEXT,
    operator_id     VARCHAR(128),
    operator_name   VARCHAR(128),
    status          VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    duration_ms     BIGINT,
    request_params  TEXT,
    response_result TEXT,
    error_stack     TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sys_operation_log_trace ON sys_operation_log(trace_id);
CREATE INDEX idx_sys_operation_log_operator ON sys_operation_log(operator_id);
CREATE INDEX idx_sys_operation_log_created ON sys_operation_log(created_at);

-- ============================================
-- 26. 系统用户表
-- ============================================
CREATE TABLE sys_user (
    id              VARCHAR(36) PRIMARY KEY,
    username        VARCHAR(64) NOT NULL UNIQUE,
    password        VARCHAR(256) NOT NULL,
    nickname        VARCHAR(128),
    email           VARCHAR(128),
    phone           VARCHAR(32),
    avatar          TEXT,
    roles           VARCHAR(512),
    permissions     TEXT,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_login_at   TIMESTAMP,
    last_login_ip   VARCHAR(64),
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Disable referential integrity for tests (FK constraints cause issues with 
-- soft-delete + unique constraint combos in H2)
SET REFERENTIAL_INTEGRITY FALSE;

-- ============================================
-- 27. 系统角色表
-- ============================================
CREATE TABLE sys_role (
    id              VARCHAR(36) PRIMARY KEY,
    role_code       VARCHAR(64) NOT NULL UNIQUE,
    role_name       VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    sort_order      INT NOT NULL DEFAULT 0,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Disable referential integrity for tests (FK constraints cause issues with 
-- soft-delete + unique constraint combos in H2)
SET REFERENTIAL_INTEGRITY FALSE;

-- ============================================
-- 28. 用户角色关联表
-- ============================================
CREATE TABLE sys_user_role (
    id              VARCHAR(36) PRIMARY KEY,
    user_id         VARCHAR(36) NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    role_id         VARCHAR(36) NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, role_id)
);

-- ============================================
-- 29. 字典类型表
-- ============================================
CREATE TABLE sys_dict (
    id              VARCHAR(36) PRIMARY KEY,
    dict_code       VARCHAR(64) NOT NULL UNIQUE,
    dict_name       VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    sort_order      INT NOT NULL DEFAULT 0,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Disable referential integrity for tests (FK constraints cause issues with 
-- soft-delete + unique constraint combos in H2)
SET REFERENTIAL_INTEGRITY FALSE;

-- ============================================
-- 30. 字典项表
-- ============================================
CREATE TABLE sys_dict_item (
    id              VARCHAR(36) PRIMARY KEY,
    dict_id         VARCHAR(36) NOT NULL REFERENCES sys_dict(id) ON DELETE CASCADE,
    item_value      VARCHAR(256) NOT NULL,
    item_label      VARCHAR(256) NOT NULL,
    description     VARCHAR(512),
    sort_order      INT NOT NULL DEFAULT 0,
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Disable referential integrity for tests (FK constraints cause issues with 
-- soft-delete + unique constraint combos in H2)
SET REFERENTIAL_INTEGRITY FALSE;

CREATE INDEX idx_sys_dict_item_dict ON sys_dict_item(dict_id);

-- ============================================
-- 31. 系统配置表
-- ============================================
CREATE TABLE sys_config (
    id              VARCHAR(36) PRIMARY KEY,
    config_key      VARCHAR(128) NOT NULL UNIQUE,
    config_name     VARCHAR(128) NOT NULL,
    config_value    TEXT,
    config_type     VARCHAR(32) NOT NULL DEFAULT 'STRING',
    description     VARCHAR(512),
    is_system       BOOLEAN NOT NULL DEFAULT FALSE,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 运行时链路 span 表 (P2-1)
CREATE TABLE lg_runtime_trace (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL,
    version_id      VARCHAR(36),
    trace_id        VARCHAR(128),
    span_id         VARCHAR(128),
    parent_span_id  VARCHAR(128),
    service_name    VARCHAR(256),
    operation_name  VARCHAR(512),
    span_kind       VARCHAR(32),
    duration_ms     BIGINT,
    status          VARCHAR(32) NOT NULL DEFAULT 'OK',
    started_at      TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT NOT NULL DEFAULT 0
);

-- ============================================
-- AgentRun 合约表
-- ============================================
CREATE TABLE lg_agent_run (
    id                       BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    contract_id              VARCHAR(64) NOT NULL UNIQUE,
    project_id               VARCHAR(36),
    agent_type               VARCHAR(64) NOT NULL,
    agent_name               VARCHAR(128),
    input_schema_version     VARCHAR(30),
    output_schema_version    VARCHAR(30),
    used_evidence_ids        TEXT,
    omitted_because          TEXT,
    needs_human_review       SMALLINT NOT NULL DEFAULT 0,
    model                    VARCHAR(100),
    prompt_tokens            INT,
    completion_tokens        INT,
    cost_usd                 DOUBLE PRECISION,
    retry_count              INT NOT NULL DEFAULT 0,
    self_correction_count    INT NOT NULL DEFAULT 0,
    quality_score            DOUBLE PRECISION,
    prompt_run_id            BIGINT,
    started_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at              TIMESTAMP,
    metadata                 TEXT,
    created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_agent_run_project ON lg_agent_run(project_id);
CREATE INDEX idx_lg_agent_run_agent ON lg_agent_run(agent_type);
CREATE INDEX idx_lg_agent_run_prompt ON lg_agent_run(prompt_run_id);

-- ============================================
-- KnowledgeClaim / GapTask 统一断言层
-- ============================================
CREATE TABLE lg_knowledge_claim (
    id                      VARCHAR(36) PRIMARY KEY,
    project_id              VARCHAR(36) NOT NULL,
    version_id              VARCHAR(36) NOT NULL,
    subject_type            VARCHAR(64) NOT NULL,
    subject_key             VARCHAR(512) NOT NULL,
    predicate               VARCHAR(64) NOT NULL,
    object_type             VARCHAR(64),
    object_key              VARCHAR(512),
    object_value            TEXT,
    qualifiers              TEXT NOT NULL DEFAULT '{}',
    evidence_ids            TEXT NOT NULL DEFAULT '[]',
    supporting_claim_ids    TEXT NOT NULL DEFAULT '[]',
    contradicting_claim_ids TEXT NOT NULL DEFAULT '[]',
    source_type             VARCHAR(64) NOT NULL,
    extractor               VARCHAR(128),
    confidence              NUMERIC(5,4) NOT NULL DEFAULT 0.5000,
    status                  VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRM',
    lineage                 TEXT NOT NULL DEFAULT '[]',
    compiled_node_id        VARCHAR(36),
    compiled_edge_id        VARCHAR(36),
    compile_status          VARCHAR(32) NOT NULL DEFAULT 'NEW',
    deleted                 SMALLINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_id, subject_type, subject_key, predicate, object_type, object_key)
);

CREATE INDEX idx_lg_knowledge_claim_project_version
    ON lg_knowledge_claim(project_id, version_id);
CREATE INDEX idx_lg_knowledge_claim_subject
    ON lg_knowledge_claim(project_id, version_id, subject_type, subject_key);
CREATE INDEX idx_lg_knowledge_claim_predicate
    ON lg_knowledge_claim(project_id, version_id, predicate);
CREATE INDEX idx_lg_knowledge_claim_status
    ON lg_knowledge_claim(project_id, version_id, status);

CREATE TABLE lg_gap_task (
    id                  VARCHAR(36) PRIMARY KEY,
    project_id          VARCHAR(36) NOT NULL,
    version_id          VARCHAR(36) NOT NULL,
    gap_type            VARCHAR(64) NOT NULL,
    gap_key             VARCHAR(512) NOT NULL,
    title               VARCHAR(512) NOT NULL,
    description         TEXT,
    severity            VARCHAR(32) NOT NULL DEFAULT 'MEDIUM',
    status              VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    subject_type        VARCHAR(64),
    subject_key         VARCHAR(512),
    related_claim_ids   TEXT NOT NULL DEFAULT '[]',
    related_node_ids    TEXT NOT NULL DEFAULT '[]',
    evidence_ids        TEXT NOT NULL DEFAULT '[]',
    suggested_action    TEXT,
    agent_run_id        BIGINT,
    priority_score      NUMERIC(5,4) NOT NULL DEFAULT 0.5000,
    deleted             SMALLINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_id, gap_type, gap_key)
);

CREATE INDEX idx_lg_gap_task_project_version ON lg_gap_task(project_id, version_id);
CREATE INDEX idx_lg_gap_task_type ON lg_gap_task(project_id, version_id, gap_type);
CREATE INDEX idx_lg_gap_task_status ON lg_gap_task(project_id, version_id, status);

-- ============================================
-- DomainOntology 领域本体
-- ============================================
CREATE TABLE lg_domain_ontology_term (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL,
    term            VARCHAR(255) NOT NULL,
    canonical_term  VARCHAR(255),
    aliases         TEXT NOT NULL DEFAULT '[]',
    category        VARCHAR(64),
    source          VARCHAR(32),
    reviewed        BOOLEAN NOT NULL DEFAULT FALSE,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, term)
);

CREATE INDEX idx_lg_domain_ontology_term_project ON lg_domain_ontology_term(project_id);
CREATE INDEX idx_lg_domain_ontology_term_category ON lg_domain_ontology_term(project_id, category);

CREATE TABLE lg_domain_ontology_relation (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL,
    from_term_id    VARCHAR(36) NOT NULL,
    to_term_id      VARCHAR(36) NOT NULL,
    relation_type   VARCHAR(32) NOT NULL,
    confidence      NUMERIC(5,4),
    source          VARCHAR(32),
    reviewed        BOOLEAN NOT NULL DEFAULT FALSE,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_domain_ontology_rel_project ON lg_domain_ontology_relation(project_id);
CREATE INDEX idx_lg_domain_ontology_rel_from ON lg_domain_ontology_relation(project_id, from_term_id);
CREATE INDEX idx_lg_domain_ontology_rel_to ON lg_domain_ontology_relation(project_id, to_term_id);
CREATE INDEX idx_lg_domain_ontology_rel_type ON lg_domain_ontology_relation(project_id, relation_type);

-- ============================================
-- 资产快照 / 图谱写入意图
-- ============================================
CREATE TABLE lg_source_asset_snapshot (
    id                    VARCHAR(64) PRIMARY KEY,
    project_id            VARCHAR(64) NOT NULL,
    version_id            VARCHAR(64) NOT NULL,
    repo_id               VARCHAR(64),
    asset_kind            VARCHAR(32) NOT NULL DEFAULT 'CODE',
    relative_path         TEXT NOT NULL,
    content_hash          VARCHAR(128),
    file_size             BIGINT DEFAULT 0,
    last_modified_ms      BIGINT DEFAULT 0,
    extractor_version     VARCHAR(128),
    scan_status           VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    previous_snapshot_id  VARCHAR(64),
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_asset_snapshot_unique
    ON lg_source_asset_snapshot(project_id, version_id, relative_path);
CREATE INDEX idx_asset_snapshot_status
    ON lg_source_asset_snapshot(project_id, version_id, scan_status);

CREATE TABLE lg_graph_write_intent (
    id                VARCHAR(64) PRIMARY KEY,
    project_id        VARCHAR(64) NOT NULL,
    version_id        VARCHAR(64) NOT NULL,
    idempotency_key   VARCHAR(256) NOT NULL,
    source            VARCHAR(32) NOT NULL DEFAULT 'SCAN',
    payload_json      TEXT NOT NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count       INTEGER DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at       TIMESTAMP
);

CREATE UNIQUE INDEX idx_gwi_idempotency ON lg_graph_write_intent(idempotency_key);
CREATE INDEX idx_gwi_status ON lg_graph_write_intent(status, created_at);
CREATE INDEX idx_gwi_project_version ON lg_graph_write_intent(project_id, version_id);

-- ============================================
-- 代码理解工具运行与证据
-- ============================================
CREATE TABLE lg_tool_run (
    id                VARCHAR(36) PRIMARY KEY,
    project_id        VARCHAR(36) NOT NULL,
    version_id        VARCHAR(36),
    tool_endpoint_id  VARCHAR(36),
    tool_name         VARCHAR(128) NOT NULL,
    tool_kind         VARCHAR(32) NOT NULL,
    operation         VARCHAR(64) NOT NULL,
    query_hash        VARCHAR(64),
    status            VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    exit_code         INTEGER,
    elapsed_ms        BIGINT,
    index_freshness   VARCHAR(32) DEFAULT 'UNKNOWN',
    stdout_sha256     VARCHAR(64),
    stdout_excerpt    TEXT,
    error_excerpt     TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tool_run_project ON lg_tool_run(project_id);
CREATE INDEX idx_tool_run_tool_name ON lg_tool_run(tool_name);
CREATE INDEX idx_tool_run_status ON lg_tool_run(status);
CREATE INDEX idx_tool_run_created ON lg_tool_run(created_at);

CREATE TABLE lg_tool_evidence (
    id                VARCHAR(36) PRIMARY KEY,
    tool_run_id       VARCHAR(36) NOT NULL,
    evidence_type     VARCHAR(32) NOT NULL,
    source_path       VARCHAR(1024),
    symbol_qn         VARCHAR(512),
    line_start        INTEGER,
    line_end          INTEGER,
    content_sha256    VARCHAR(64),
    excerpt           TEXT,
    graph_node_key    VARCHAR(256),
    claim_id          VARCHAR(36),
    confidence        DOUBLE PRECISION DEFAULT 0.5,
    privacy_level     VARCHAR(32) DEFAULT 'INTERNAL',
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tool_evidence_run ON lg_tool_evidence(tool_run_id);
CREATE INDEX idx_tool_evidence_type ON lg_tool_evidence(evidence_type);
CREATE INDEX idx_tool_evidence_claim ON lg_tool_evidence(claim_id);
CREATE INDEX idx_tool_evidence_path ON lg_tool_evidence(source_path);

-- ============================================
-- ChangeTask 变更闭环（V9 对应，增强版2）
-- ============================================
CREATE TABLE lg_change_task (
    id                  VARCHAR(36) PRIMARY KEY,
    project_id          VARCHAR(36) NOT NULL,
    version_id          VARCHAR(36),
    task_type           VARCHAR(32)  NOT NULL,
    title               VARCHAR(255) NOT NULL,
    input_issue         TEXT,
    impacted_subgraph   TEXT,
    proposal            TEXT,
    risk_level          VARCHAR(16),
    status              VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    agent_run_id        VARCHAR(64),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0
);

CREATE TABLE lg_patch_file (
    id                  VARCHAR(36) PRIMARY KEY,
    change_task_id      VARCHAR(36) NOT NULL,
    file_path           TEXT         NOT NULL,
    change_type         VARCHAR(32)  NOT NULL,
    before_sha          VARCHAR(64),
    after_sha           VARCHAR(64),
    patch_text          TEXT         NOT NULL,
    generated_by        VARCHAR(64)  NOT NULL,
    evidence_ids        TEXT,
    status              VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0
);

CREATE TABLE lg_validation_gate (
    id                  VARCHAR(36) PRIMARY KEY,
    change_task_id      VARCHAR(36) NOT NULL,
    gate_type           VARCHAR(32)  NOT NULL,
    command             TEXT,
    result              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    report_uri          TEXT,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0
);

CREATE TABLE lg_pr_task (
    id                  VARCHAR(36) PRIMARY KEY,
    change_task_id      VARCHAR(36) NOT NULL,
    branch_name         VARCHAR(255) NOT NULL,
    pr_url              TEXT,
    pr_status           VARCHAR(32)  NOT NULL DEFAULT 'NOT_CREATED',
    reviewer_policy     TEXT,
    rollback_plan       TEXT,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0
);

-- Disable referential integrity for tests (FK constraints cause issues with
-- soft-delete + unique constraint combos in H2)
SET REFERENTIAL_INTEGRITY FALSE;
