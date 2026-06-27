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

-- ============================================
-- 15. 系统操作日志表 (审计日志)
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
-- 16. 系统用户表
-- ============================================
CREATE TABLE sys_user (
    id              UUID PRIMARY KEY,
    username        VARCHAR(64) NOT NULL UNIQUE,
    password        VARCHAR(256) NOT NULL,
    nickname        VARCHAR(128),
    email           VARCHAR(128),
    phone           VARCHAR(32),
    avatar          TEXT,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_login_at   TIMESTAMP,
    last_login_ip   VARCHAR(64),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_user IS '系统用户表';

-- 插入默认管理员用户 (密码: admin123 已 BCrypt 加密)
INSERT INTO sys_user (id, username, password, nickname, email, status)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'admin',
    '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH',
    '系统管理员',
    'admin@legacygraph.io',
    'ACTIVE'
);

-- ============================================
-- 17. 系统角色表
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
-- 18. 用户角色关联表
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
-- 19. 字典类型表
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
-- 20. 字典项表
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
-- 21. 系统配置表
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
-- 22. 迁移风险表
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
