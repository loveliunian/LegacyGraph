-- ============================================
-- LegacyGraph H2 测试数据初始化
-- 基础数据：管理员用户、角色、系统配置
-- ============================================

-- 测试项目（供 Controller 测试使用）
INSERT INTO lg_project (id, project_code, project_name, project_type, status, owner, deleted, created_at, updated_at)
VALUES ('test-project-1', 'TEST-PROJ-1', 'Test Project 1', 'LEGACY', 'ACTIVE', 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO lg_project (id, project_code, project_name, project_type, status, owner, deleted, created_at, updated_at)
VALUES ('project-1', 'PROJ-1', 'Project 1', 'LEGACY', 'ACTIVE', 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 测试扫描版本（供 Controller 测试使用）
INSERT INTO lg_scan_version (id, project_id, version_no, scan_status, deleted, created_at, updated_at)
VALUES ('version-1', 'test-project-1', 'v1.0', 'CREATED', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO lg_scan_version (id, project_id, version_no, scan_status, deleted, created_at, updated_at)
VALUES ('version-2', 'project-1', 'v1.0', 'CREATED', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 测试节点（供 GraphQuery/LlmAgent Controller 测试使用）
INSERT INTO lg_graph_node (id, project_id, version_id, node_type, node_key, node_name, status, deleted, created_at, updated_at)
VALUES ('node-1', 'test-project-1', 'version-1', 'ENTITY', 'node-key-1', 'NodeA', 'PENDING_CONFIRM', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO lg_graph_node (id, project_id, version_id, node_type, node_key, node_name, status, deleted, created_at, updated_at)
VALUES ('node-2', 'test-project-1', 'version-1', 'ENTITY', 'node-key-2', 'NodeB', 'PENDING_CONFIRM', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO lg_graph_node (id, project_id, version_id, node_type, node_key, node_name, status, deleted, created_at, updated_at)
VALUES ('target-1', 'test-project-1', 'version-1', 'ENTITY', 'target-key', 'TargetNode', 'PENDING_CONFIRM', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO lg_graph_node (id, project_id, version_id, node_type, node_key, node_name, status, deleted, created_at, updated_at)
VALUES ('merge-1', 'test-project-1', 'version-1', 'ENTITY', 'merge-key', 'MergeNode', 'PENDING_CONFIRM', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 默认管理员用户 (密码: admin123 已 BCrypt 加密)
INSERT INTO sys_user (id, username, password, nickname, email, status)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'admin',
    '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH',
    '系统管理员',
    'admin@legacygraph.io',
    'ACTIVE'
);

-- 默认角色
INSERT INTO sys_role (id, role_code, role_name, description, sort_order)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'ADMIN', '系统管理员', '拥有所有权限', 1),
    ('00000000-0000-0000-0000-000000000002', 'DEVELOPER', '开发人员', '可以项目管理和扫描', 2),
    ('00000000-0000-0000-0000-000000000003', 'REVIEWER', '审核人员', '可以审核图谱事实', 3);

-- 关联管理员用户到管理员角色
INSERT INTO sys_user_role (id, user_id, role_id)
VALUES ('00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000001');

-- 默认系统配置
INSERT INTO sys_config (id, config_key, config_name, config_value, config_type, is_system)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'system.name', '系统名称', 'LegacyGraph', 'STRING', TRUE),
    ('00000000-0000-0000-0000-000000000002', 'system.version', '系统版本', '1.0.0', 'STRING', TRUE),
    ('00000000-0000-0000-0000-000000000003', 'llm.timeout', 'LLM 调用超时', '60000', 'NUMBER', TRUE),
    ('00000000-0000-0000-0000-000000000004', 'llm.max_tokens', 'LLM 最大令牌数', '4096', 'NUMBER', TRUE);
