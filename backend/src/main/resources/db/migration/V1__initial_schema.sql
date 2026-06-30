-- ============================================
-- LegacyGraph 数据库初始化脚本 - Flyway 合并版本
-- 数据库: PostgreSQL 15+
-- 说明: 此脚本与当前实体代码完全一致 (2026-06-30 合并)
-- ============================================

-- 尝试启用 pgvector 扩展（如果不可用则跳过，不影响其他表创建）
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;
    RAISE NOTICE 'pgvector 扩展已启用';
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pgvector 扩展不可用，跳过: %', SQLERRM;
END $$;

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
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE lg_project IS '项目表';

-- ============================================
-- 2. 代码仓库表
-- ============================================
CREATE TABLE lg_code_repo (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    repo_name       VARCHAR(256) NOT NULL,
    repo_type       VARCHAR(32) NOT NULL DEFAULT 'GIT',
    git_url         TEXT NOT NULL,
    branch_name     VARCHAR(128),
    auth_type       VARCHAR(32) DEFAULT 'NONE',
    username        VARCHAR(128),
    include_pattern TEXT,
    exclude_pattern TEXT,
    local_path      TEXT,
    status          VARCHAR(32) NOT NULL DEFAULT 'NEW',
    last_pull_status VARCHAR(32),
    last_pull_time  TIMESTAMP,
    last_scan_time  TIMESTAMP,
    created_by       VARCHAR(128),
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_code_repo_project ON lg_code_repo(project_id);

COMMENT ON TABLE lg_code_repo IS '代码仓库表';

-- ============================================
-- 3. 数据库连接表
-- ============================================
CREATE TABLE lg_db_connection (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    connection_name VARCHAR(128) NOT NULL,
    db_type         VARCHAR(32) NOT NULL,
    host            VARCHAR(128) NOT NULL,
    port            INT NOT NULL,
    database_name   VARCHAR(128) NOT NULL,
    schema_name      VARCHAR(128),
    username        VARCHAR(128),
    password        VARCHAR(256),
    readonly        BOOLEAN DEFAULT FALSE,
    include_tables   TEXT,
    exclude_tables   TEXT,
    status          VARCHAR(32) NOT NULL DEFAULT 'NEW',
    table_count      INT,
    last_scan_time   TIMESTAMP,
    created_by       VARCHAR(128),
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_db_connection_project ON lg_db_connection(project_id);

COMMENT ON TABLE lg_db_connection IS '数据库连接表';

-- ============================================
-- 4. 上传文档表
-- ============================================
CREATE TABLE lg_document (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id       UUID,
    doc_name        VARCHAR(512) NOT NULL,
    doc_type         VARCHAR(64),
    file_type        VARCHAR(32),
    file_path        TEXT,
    file_size        BIGINT,
    parse_status     VARCHAR(32) DEFAULT 'PENDING',
    fact_count       INT DEFAULT 0,
    error_message    TEXT,
    uploaded_by      VARCHAR(128),
    uploaded_at      TIMESTAMP,
    parsed_at        TIMESTAMP,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_document_project ON lg_document(project_id);

COMMENT ON TABLE lg_document IS '上传文档表';

-- ============================================
-- 5. 扫描版本表
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
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_no)
);

COMMENT ON TABLE lg_scan_version IS '项目扫描版本表';

CREATE INDEX idx_lg_scan_version_project ON lg_scan_version(project_id);

-- ============================================
-- 6. 扫描任务表
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
-- 7. 原始事实表
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
    source_line     INT,
    content_summary TEXT,
    raw_content     TEXT,
    normalized_data JSONB NOT NULL,
    confidence      NUMERIC(5,4) NOT NULL DEFAULT 1.0000,
    status          VARCHAR(32) NOT NULL DEFAULT 'EXTRACTED',
    mapped_to_graph BOOLEAN,
    related_node_count INT,
    created_by       VARCHAR(128),
    -- LLM 集成字段
    evidence_ids    JSONB NOT NULL DEFAULT '[]'::JSONB,
    extractor_name  VARCHAR(100),
    extractor_version VARCHAR(30),
    prompt_run_id   BIGINT,
    pii_masked      BOOLEAN NOT NULL DEFAULT FALSE,
    review_status   VARCHAR(20) DEFAULT 'pending',
    verified_by_test BOOLEAN NOT NULL DEFAULT FALSE,
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
-- 8. 证据表
-- ============================================
CREATE TABLE lg_evidence (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
    evidence_type   VARCHAR(64) NOT NULL,
    source_path     TEXT,
    source_name     VARCHAR(256),
    start_line      INT,
    end_line        INT,
    content_hash    VARCHAR(128),
    content_excerpt TEXT,
    summary         TEXT,
    content         TEXT,
    metadata        JSONB,
    ast_path        TEXT,
    sql_hash        VARCHAR(128),
    chunk_id        BIGINT,
    related_node_ids TEXT,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_evidence_project_version ON lg_evidence(project_id, version_id);

COMMENT ON TABLE lg_evidence IS '证据表 - 所有 LLM 输出都必须可追溯到证据';

-- ============================================
-- 9. 节点证据关联表
-- ============================================
CREATE TABLE lg_node_evidence (
    id              UUID PRIMARY KEY,
    node_id         UUID NOT NULL,
    evidence_id     UUID NOT NULL REFERENCES lg_evidence(id),
    relation_type   VARCHAR(64) NOT NULL DEFAULT 'DERIVED_FROM',
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(node_id, evidence_id)
);

COMMENT ON TABLE lg_node_evidence IS '节点证据关联表';

-- ============================================
-- 10. 关系证据关联表
-- ============================================
CREATE TABLE lg_edge_evidence (
    id              UUID PRIMARY KEY,
    edge_id         UUID NOT NULL,
    evidence_id     UUID NOT NULL REFERENCES lg_evidence(id),
    relation_type   VARCHAR(64) NOT NULL DEFAULT 'DERIVED_FROM',
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(edge_id, evidence_id)
);

COMMENT ON TABLE lg_edge_evidence IS '关系证据关联表';

-- ============================================
-- 11. 文档片段表
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
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_doc_chunk_project_version ON lg_doc_chunk(project_id, version_id);

COMMENT ON TABLE lg_doc_chunk IS '文档片段表';

-- ============================================
-- 12. 向量文档表（依赖 pgvector 扩展，不可用时跳过）
-- ============================================
DO $$
BEGIN
    CREATE TABLE IF NOT EXISTS vector_document (
        id              UUID PRIMARY KEY,
        project_id      UUID NOT NULL REFERENCES lg_project(id),
        content         TEXT NOT NULL,
        embedding       vector(768),
        metadata        JSONB,
        created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

    CREATE INDEX IF NOT EXISTS idx_vector_document_embedding
        ON vector_document USING hnsw (embedding vector_cosine_ops);

    RAISE NOTICE '向量文档表创建成功';
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE '向量文档表创建跳过（pgvector 不可用）: %', SQLERRM;
END $$;

-- ============================================
-- 13. 测试用例表
-- ============================================
CREATE TABLE lg_test_case (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
    case_code       VARCHAR(128) NOT NULL,
    case_name       VARCHAR(512) NOT NULL,
    case_type       VARCHAR(64) NOT NULL,
    scenario        VARCHAR(64),
    target_node_id  UUID,
    priority        VARCHAR(32) NOT NULL DEFAULT 'P2',
    preconditions   JSONB,
    steps           JSONB NOT NULL,
    expected_result JSONB NOT NULL,
    generated_by     VARCHAR(64) NOT NULL,
    confidence      NUMERIC(5,4) NOT NULL DEFAULT 0.8000,
    status          VARCHAR(32) NOT NULL DEFAULT 'GENERATED',
    deleted         INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_id, case_code)
);

CREATE INDEX idx_lg_test_case_target ON lg_test_case(target_node_id);

COMMENT ON TABLE lg_test_case IS '测试用例表';

-- ============================================
-- 14. 测试断言表
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
    deleted         SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE lg_test_assertion IS '测试断言表';

-- ============================================
-- 15. 测试结果表
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
    deleted         SMALLINT NOT NULL DEFAULT 0,
    executed_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_test_result_case ON lg_test_result(test_case_id);
CREATE INDEX idx_lg_test_result_execution ON lg_test_result(execution_id);

COMMENT ON TABLE lg_test_result IS '测试结果表';

-- ============================================
-- 18. 测试运行表
-- ============================================
CREATE TABLE lg_test_run (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
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

COMMENT ON TABLE lg_test_run IS '测试运行表';

-- ============================================
-- 17. 人工确认记录表
-- ============================================
CREATE TABLE lg_review_record (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
    target_type     VARCHAR(64) NOT NULL,
    target_id       UUID NOT NULL,
    target_name     VARCHAR(256),
    graph_type       VARCHAR(64),
    confidence      NUMERIC(5,4),
    evidence_count  INT,
    priority        VARCHAR(32),
    status          VARCHAR(32),
    assignee        VARCHAR(128),
    comment         TEXT,
    reviewed_by     VARCHAR(128),
    reviewed_at     TIMESTAMP,
    before_data     JSONB,
    after_data      JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE lg_review_record IS '人工确认记录表';

-- ============================================
-- 18. 迁移风险表
-- ============================================
CREATE TABLE migration_risk (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
    risk_type       VARCHAR(64) NOT NULL,
    risk_name       VARCHAR(256) NOT NULL,
    description     TEXT,
    affected_nodes  JSONB,
    severity        VARCHAR(32) NOT NULL DEFAULT 'MEDIUM',
    status          VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    mitigation      TEXT,
    estimated_effort INT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_migration_risk_project ON migration_risk(project_id);
CREATE INDEX idx_migration_risk_severity ON migration_risk(severity);
CREATE INDEX idx_migration_risk_status ON migration_risk(status);

COMMENT ON TABLE migration_risk IS '迁移风险表';

-- ============================================
-- 19. LLM 提供者配置表
-- ============================================
CREATE TABLE lg_llm_provider (
    id              BIGSERIAL PRIMARY KEY,
    provider_code   VARCHAR(50) NOT NULL UNIQUE,
    model_id        VARCHAR(100) NOT NULL,
    endpoint        TEXT,
    deployment_mode VARCHAR(20) NOT NULL,
    api_config      JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE lg_llm_provider IS 'LLM 提供者配置表';

-- ============================================
-- 20. Prompt 模板表
-- ============================================
CREATE TABLE lg_prompt_template (
    id              BIGSERIAL PRIMARY KEY,
    template_code   VARCHAR(100) NOT NULL UNIQUE,
    version         VARCHAR(30) NOT NULL,
    scene           VARCHAR(50) NOT NULL,
    system_prompt   TEXT NOT NULL,
    domain_prompt   TEXT,
    task_prompt     TEXT NOT NULL,
    output_schema   JSONB NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE lg_prompt_template IS 'Prompt 模板表';

-- ============================================
-- 23. Prompt 运行记录表
-- ============================================
CREATE TABLE lg_prompt_run (
    id              BIGSERIAL PRIMARY KEY,
    project_id      UUID REFERENCES lg_project(id),
    task_type       VARCHAR(50) NOT NULL,
    provider_code   VARCHAR(50) NOT NULL,
    model_id        VARCHAR(100) NOT NULL,
    template_code   VARCHAR(100) NOT NULL,
    template_version VARCHAR(30) NOT NULL,
    input_hash      CHAR(64) NOT NULL,
    masked_input    JSONB NOT NULL,
    raw_output      JSONB,
    parsed_output   JSONB,
    prompt_tokens   INT,
    completion_tokens INT,
    latency_ms      INT,
    status          VARCHAR(20) NOT NULL,
    created_by      VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_prompt_run_project ON lg_prompt_run(project_id);
CREATE INDEX idx_lg_prompt_run_input_hash ON lg_prompt_run(input_hash);
CREATE INDEX idx_lg_prompt_run_status ON lg_prompt_run(status);

COMMENT ON TABLE lg_prompt_run IS 'Prompt 运行记录表';

-- ============================================
-- 24. 报告表
-- ============================================
CREATE TABLE lg_reports (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID NOT NULL REFERENCES lg_scan_version(id),
    report_type     VARCHAR(64) NOT NULL,
    report_name     VARCHAR(256) NOT NULL,
    report_data     JSONB,
    file_path       TEXT,
    error_message   TEXT,
    status          VARCHAR(32) NOT NULL DEFAULT 'GENERATING',
    deleted         SMALLINT NOT NULL DEFAULT 0,
    generated_at    TIMESTAMP,
    completed_at    TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE lg_reports IS '生成报告记录表';

-- ============================================
-- 25. 系统操作日志表 (审计日志)
-- ============================================
CREATE TABLE sys_operation_log (
    id              BIGSERIAL PRIMARY KEY,
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

COMMENT ON TABLE sys_operation_log IS '系统操作日志表 (审计日志)';

-- ============================================
-- 24. 系统用户表
-- ============================================
CREATE TABLE sys_user (
    id              UUID PRIMARY KEY,
    username        VARCHAR(64) NOT NULL UNIQUE,
    password        VARCHAR(256) NOT NULL,
    nickname        VARCHAR(128),
    email           VARCHAR(128),
    phone           VARCHAR(32),
    avatar          TEXT,
    roles           VARCHAR(256),
    permissions     VARCHAR(512),
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_login_at   TIMESTAMP,
    last_login_ip   VARCHAR(64),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_user IS '系统用户表';
COMMENT ON COLUMN sys_user.roles IS '角色（逗号分隔）';
COMMENT ON COLUMN sys_user.permissions IS '权限（逗号分隔）';

-- 插入默认管理员用户 (密码: admin123 已 BCrypt 加密)
INSERT INTO sys_user (id, username, password, nickname, email, roles, permissions, status)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'admin',
    '$2b$12$k8.YwrBI4R4fx0fl.h3bMeaBfNd3IiCmfTNuvoS.z.yDFHvUoJ0dC',
    '系统管理员',
    'admin@legacygraph.io',
    'ADMIN',
    '*',
    'ACTIVE'
);

-- ============================================
-- 25. 系统角色表
-- ============================================
CREATE TABLE sys_role (
    id              UUID PRIMARY KEY,
    role_code       VARCHAR(64) NOT NULL UNIQUE,
    role_name       VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    sort_order      INT NOT NULL DEFAULT 0,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_role IS '系统角色表';

-- 插入默认角色
INSERT INTO sys_role (id, role_code, role_name, description, sort_order)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'ADMIN', '系统管理员', '拥有所有权限', 1),
    ('00000000-0000-0000-0000-000000000002', 'DEVELOPER', '开发人员', '可以项目管理和扫描', 2),
    ('00000000-0000-0000-0000-000000000003', 'REVIEWER', '审核人员', '可以审核图谱事实', 3);

-- ============================================
-- 26. 用户角色关联表
-- ============================================
CREATE TABLE sys_user_role (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    role_id         UUID NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, role_id)
);

COMMENT ON TABLE sys_user_role IS '用户角色关联表';

-- 关联管理员用户到管理员角色
INSERT INTO sys_user_role (id, user_id, role_id)
VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001');

-- ============================================
-- 27. 字典类型表
-- ============================================
CREATE TABLE sys_dict (
    id              UUID PRIMARY KEY,
    dict_code       VARCHAR(64) NOT NULL UNIQUE,
    dict_name       VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    sort_order      INT NOT NULL DEFAULT 0,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_dict IS '字典类型表';

-- ============================================
-- 28. 字典项表
-- ============================================
CREATE TABLE sys_dict_item (
    id              UUID PRIMARY KEY,
    dict_id         UUID NOT NULL REFERENCES sys_dict(id) ON DELETE CASCADE,
    item_value      VARCHAR(256) NOT NULL,
    item_label      VARCHAR(256) NOT NULL,
    description     VARCHAR(512),
    sort_order      INT NOT NULL DEFAULT 0,
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sys_dict_item_dict ON sys_dict_item(dict_id);

COMMENT ON TABLE sys_dict_item IS '字典项表';

-- ============================================
-- 29. 系统配置表
-- ============================================
CREATE TABLE sys_config (
    id              UUID PRIMARY KEY,
    config_key      VARCHAR(128) NOT NULL UNIQUE,
    config_name     VARCHAR(128) NOT NULL,
    config_value    TEXT,
    config_type     VARCHAR(32) NOT NULL DEFAULT 'STRING',
    description     VARCHAR(512),
    is_system       BOOLEAN NOT NULL DEFAULT FALSE,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_config IS '系统配置表';

-- 插入默认系统配置
INSERT INTO sys_config (id, config_key, config_name, config_value, config_type, is_system)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'system.name', '系统名称', 'LegacyGraph', 'STRING', TRUE),
    ('00000000-0000-0000-0000-000000000002', 'system.version', '系统版本', '1.0.0', 'STRING', TRUE),
    ('00000000-0000-0000-0000-000000000003', 'llm.timeout', 'LLM 调用超时', '60000', 'NUMBER', TRUE),
    ('00000000-0000-0000-0000-000000000004', 'llm.max_tokens', 'LLM 最大令牌数', '4096', 'NUMBER', TRUE);

-- ============================================
-- 30. 运行时链路 span 表 (P2-1)
-- ============================================
CREATE TABLE lg_runtime_trace (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    version_id      UUID REFERENCES lg_scan_version(id),
    trace_id        VARCHAR(128),
    span_id         VARCHAR(128),
    parent_span_id  VARCHAR(128),
    service_name    VARCHAR(256),
    operation_name  VARCHAR(512),
    span_kind       VARCHAR(32),
    duration_ms     BIGINT,
    status          VARCHAR(32) NOT NULL DEFAULT 'OK',
    started_at      TIMESTAMP,
    deleted         INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_runtime_trace_project_version ON lg_runtime_trace(project_id, version_id);
CREATE INDEX idx_lg_runtime_trace_trace_id ON lg_runtime_trace(trace_id);

COMMENT ON TABLE lg_runtime_trace IS '运行时链路 span 表';

-- ============================================
-- 初始化 LLM 提供者配置和 Prompt 模板
-- ============================================

-- OpenAI 默认配置
INSERT INTO lg_llm_provider (provider_code, model_id, endpoint, deployment_mode, api_config)
VALUES ('openai', 'gpt-5.5', 'https://api.openai.com/v1', 'cloud', '{"api_key": "${OPENAI_API_KEY}"}'::JSONB)
ON CONFLICT (provider_code) DO NOTHING;

-- OpenAI GPT-5.4 用于批量处理
INSERT INTO lg_llm_provider (provider_code, model_id, endpoint, deployment_mode, api_config)
VALUES ('openai-batch', 'gpt-5.4', 'https://api.openai.com/v1', 'cloud', '{"api_key": "${OPENAI_API_KEY}"}'::JSONB)
ON CONFLICT (provider_code) DO NOTHING;

-- OpenAI Embedding
INSERT INTO lg_llm_provider (provider_code, model_id, endpoint, deployment_mode, api_config)
VALUES ('openai-embedding', 'text-embedding-3-small', 'https://api.openai.com/v1', 'cloud', '{"api_key": "${OPENAI_API_KEY}"}'::JSONB)
ON CONFLICT (provider_code) DO NOTHING;

-- 代码事实理解模板
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'code-fact-extraction',
    '1.0',
    'code',
    '你是企业级遗留系统代码分析专家。
你只能根据输入的代码事实输出结论。
不允许编造未被代码证据支持的业务语义。
输出必须严格符合 JSON Schema。',
    NULL,
    '输入是一段从代码中静态抽取的方法、类或接口定义，请：
1. 推断其业务语义和功能描述
2. 识别参数和返回值的业务含义
3. 补全可能存在的动态 SQL 分支逻辑
4. 对每条结论附上证据来源',
    '{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "FactExtractionResult",
  "type": "object",
  "required": ["factType", "projectId", "items"],
  "properties": {
    "factType": {
      "type": "string",
      "enum": ["API_ENDPOINT", "SERVICE_METHOD", "SQL_STATEMENT", "TABLE_SCHEMA", "PAGE_ACTION", "BUSINESS_PROCESS"]
    },
    "projectId": { "type": "string" },
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["key", "name", "evidence", "confidence"],
        "properties": {
          "key": { "type": "string" },
          "name": { "type": "string" },
          "attributes": { "type": "object", "additionalProperties": true },
          "evidence": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["sourceType", "sourceUri"],
              "properties": {
                "sourceType": { "type": "string" },
                "sourceUri": { "type": "string" },
                "lineStart": { "type": "integer" },
                "lineEnd": { "type": "integer" },
                "excerpt": { "type": "string" }
              }
            }
          },
          "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
        }
      }
    }
  }
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- 文档理解模板
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'doc-understanding',
    '1.0',
    'doc',
    '你是企业级遗留系统业务分析师。
你只能根据输入证据输出结论。
不允许编造未被证据支持的流程。
输出必须严格符合 JSON Schema。',
    NULL,
    '输入包括：产品文档片段、前端页面动作、后端接口定义、相关 SQL、表结构。
请抽取业务流程、参与角色、业务对象、规则与状态流转。
对每条结论附 evidence 引用。',
    '{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "BusinessProcessExtractionResult",
  "type": "object",
  "required": ["processes"],
  "properties": {
    "processes": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["key", "name", "description"],
        "properties": {
          "key": { "type": "string" },
          "name": { "type": "string" },
          "description": { "type": "string" },
          "roles": { "type": "array", "items": { "type": "string" } },
          "objects": { "type": "array", "items": { "type": "string" } },
          "rules": { "type": "array", "items": { "type": "string" } },
          "states": { "type": "array", "items": { "type": "object" } },
          "evidence": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["sourceType", "sourceUri"],
              "properties": {
                "sourceType": { "type": "string" },
                "sourceUri": { "type": "string" },
                "lineStart": { "type": "integer" },
                "lineEnd": { "type": "integer" },
                "excerpt": { "type": "string" }
              }
            }
          },
          "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
        }
      }
    }
  }
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- 图谱合并决策模板
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'graph-merge-decision',
    '1.0',
    'merge',
    '你是图谱合并专家。你的任务是判断两个图谱节点是否应该合并为一个。
仔细分析名称、语义、结构、邻居和证据，做出合理决策。
输出必须严格符合 JSON Schema。',
    NULL,
    '给定两个候选图谱节点，请判断它们是否表示同一个概念，应该合并。
分析名称相似度、语义相似度、结构重叠度、邻居相似度和证据重叠度。
给出决策：AUTO_MERGE（自动合并）、REVIEW（需要人工审核）、REJECT（拒绝合并）',
    '{
  "type": "object",
  "required": ["candidateA", "candidateB", "decision", "score"],
  "properties": {
    "candidateA": { "type": "string" },
    "candidateB": { "type": "string" },
    "decision": {
      "type": "string",
      "enum": ["AUTO_MERGE", "REVIEW", "REJECT"]
    },
    "score": { "type": "number", "minimum": 0, "maximum": 1 },
    "reasons": {
      "type": "array",
      "items": { "type": "string" }
    },
    "positiveEvidenceIds": {
      "type": "array",
      "items": { "type": "string" }
    },
    "negativeEvidenceIds": {
      "type": "array",
      "items": { "type": "string" }
    }
  }
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- 测试用例生成模板
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'test-case-generation',
    '1.0',
    'test',
    '你是测试架构师。
你必须同时生成操作步骤、接口断言、数据库断言与状态断言。
不确定的数据请显式标记 needHumanInput。
输出必须严格符合 JSON Schema。',
    NULL,
    '输入：
- 功能节点信息
- API 接口定义
- 请求参数与 DTO
- 相关写表信息
- 业务规则

请生成完整的测试用例，包括：
- 正常场景
- 权限场景
- 状态非法场景
- 数据不存在场景
- 边界场景',
    '{
  "type": "object",
  "required": ["featureKey", "caseName", "caseType", "steps", "assertions"],
  "properties": {
    "featureKey": { "type": "string" },
    "caseName": { "type": "string" },
    "caseType": {
      "type": "string",
      "enum": ["API", "E2E", "DB", "HYBRID"]
    },
    "preconditions": {
      "type": "array",
      "items": { "type": "string" }
    },
    "steps": {
      "type": "array",
      "items": { "type": "string" }
    },
    "request": { "type": "object", "additionalProperties": true },
    "assertions": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["type", "expression"],
        "properties": {
          "type": {
            "type": "string",
            "enum": ["HTTP", "JSON_PATH", "SQL", "STATE", "UI"]
          },
          "expression": { "type": "string" }
        }
      }
    },
    "needHumanInput": {
      "type": "array",
      "items": { "type": "string" }
    }
  }
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- 功能映射模板 - 页面对齐接口
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'feature-mapping',
    '1.0',
    'code',
    '你是功能映射专家。你的任务是将页面、按钮、接口、权限和业务动作建立可追溯关系。
输出必须严格符合 JSON Schema。',
    NULL,
    '输入：
1. Vue 页面组件代码
2. axios/request 定义
3. Spring Controller 接口
4. 权限注解
5. 产品文档功能清单

请输出：
- 已确认映射关系
- 可能映射关系
- 未匹配项
- 每条关系的证据、置信度、冲突点',
    '{
  "type": "object",
  "required": ["mappings"],
  "properties": {
    "mappings": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["pageKey", "buttonName", "apiKey", "businessAction", "confidence"],
        "properties": {
          "pageKey": { "type": "string" },
          "buttonName": { "type": "string" },
          "apiKey": { "type": "string" },
          "businessAction": { "type": "string" },
          "permissionKey": { "type": "string" },
          "confidence": { "type": "number", "minimum": 0, "maximum": 1 },
          "evidence": { "type": "array", "items": { "type": "object" } },
          "conflicts": { "type": "array", "items": { "type": "string" } }
        }
      }
    },
    "unmatched": {
      "type": "array",
      "items": { "type": "string" }
    }
  }
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;
