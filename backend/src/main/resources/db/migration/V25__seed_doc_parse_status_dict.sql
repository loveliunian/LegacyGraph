-- ============================================
-- V25: 新增文档解析状态字典（doc_parse_status）
--      补充扫描状态字典缺失项（SKIPPED）
--      新增变更任务类型/状态字典（change_task_type / change_task_status）
--      新增图谱节点类型字典（graph_node_type）
-- ============================================

-- ==================== 字典类型 ====================

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'a1b2c3d4-5678-9abc-def0-1234567890ab', 'doc_parse_status', '文档解析状态', '上传文档的解析生命周期状态', 15, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'doc_parse_status');

-- ==================== 字典项 ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fe01a001-0001-4000-a001-000000000001', 'a1b2c3d4-5678-9abc-def0-1234567890ab', 'PENDING',      '待处理',   '文档已创建，等待上传或发现',           1, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'a1b2c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'PENDING');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fe01a001-0001-4000-a001-000000000002', 'a1b2c3d4-5678-9abc-def0-1234567890ab', 'DISCOVERED',   '已发现',   '由自动发现流程检测到的文档',           2, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'a1b2c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'DISCOVERED');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fe01a001-0001-4000-a001-000000000003', 'a1b2c3d4-5678-9abc-def0-1234567890ab', 'UPLOADED',     '已上传',   '文档已上传，等待解析',                 3, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'a1b2c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'UPLOADED');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fe01a001-0001-4000-a001-000000000004', 'a1b2c3d4-5678-9abc-def0-1234567890ab', 'PARSING',      '解析中',   '正在对文档进行文本抽取与切片',         4, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'a1b2c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'PARSING');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fe01a001-0001-4000-a001-000000000005', 'a1b2c3d4-5678-9abc-def0-1234567890ab', 'PARSED',       '已解析',   '文档解析完成，已生成文本片段',         5, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'a1b2c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'PARSED');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fe01a001-0001-4000-a001-000000000006', 'a1b2c3d4-5678-9abc-def0-1234567890ab', 'PARSE_FAILED', '解析失败', '文档解析过程中发生错误',               6, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'a1b2c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'PARSE_FAILED');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fe01a001-0001-4000-a001-000000000007', 'a1b2c3d4-5678-9abc-def0-1234567890ab', 'FAILED',       '解析失败', '文档解析失败（旧状态码，与 PARSE_FAILED 等价）', 7, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'a1b2c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'FAILED');

-- ==================== 补充 scan_status 缺失项 ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'e493f5ac-0001-5000-a001-000000000001', 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0', 'SKIPPED',     '已跳过',   '扫描阶段已跳过',       10, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0' AND item_value = 'SKIPPED');

-- ==================== 字典类型 — change_task_type / change_task_status ====================

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'b201c3d4-5678-9abc-def0-1234567890ab', 'change_task_type',   '变更任务类型', '变更任务的类型分类',                        16, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'change_task_type');

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'b301c3d4-5678-9abc-def0-1234567890ab', 'change_task_status', '变更任务状态', '变更任务的生命周期状态',                    17, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'change_task_status');

-- ==================== 字典项 — change_task_type ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fc01a001-0001-4000-a002-000000000001', 'b201c3d4-5678-9abc-def0-1234567890ab', 'BUGFIX',   'Bug修复', '修复代码缺陷',                  1, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b201c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'BUGFIX');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fc01a001-0001-4000-a002-000000000002', 'b201c3d4-5678-9abc-def0-1234567890ab', 'REFACTOR', '重构',   '代码重构与优化',                 2, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b201c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'REFACTOR');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fc01a001-0001-4000-a002-000000000003', 'b201c3d4-5678-9abc-def0-1234567890ab', 'UPGRADE',  '升级',   '系统或依赖版本升级',             3, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b201c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'UPGRADE');

-- ==================== 字典项 — change_task_status ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fc02a001-0001-4000-a002-000000000001', 'b301c3d4-5678-9abc-def0-1234567890ab', 'OPEN',               '待处理',     '任务已创建，等待处理',                 1, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b301c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'OPEN');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fc02a001-0001-4000-a002-000000000002', 'b301c3d4-5678-9abc-def0-1234567890ab', 'IMPACT_READY',       '影响就绪',   '影响分析已完成',                       2, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b301c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'IMPACT_READY');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fc02a001-0001-4000-a002-000000000003', 'b301c3d4-5678-9abc-def0-1234567890ab', 'PATCH_DRAFTED',       '补丁已生成', '补丁方案已生成，待审核',               3, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b301c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'PATCH_DRAFTED');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fc02a001-0001-4000-a002-000000000004', 'b301c3d4-5678-9abc-def0-1234567890ab', 'REVIEW_PENDING',      '待审核',     '等待人工审核',                         4, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b301c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'REVIEW_PENDING');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fc02a001-0001-4000-a002-000000000005', 'b301c3d4-5678-9abc-def0-1234567890ab', 'VALIDATING',          '验证中',     '正在执行验证',                         5, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b301c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'VALIDATING');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fc02a001-0001-4000-a002-000000000006', 'b301c3d4-5678-9abc-def0-1234567890ab', 'VALIDATION_PASSED',   '验证通过',   '验证全部通过',                         6, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b301c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'VALIDATION_PASSED');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fc02a001-0001-4000-a002-000000000007', 'b301c3d4-5678-9abc-def0-1234567890ab', 'VALIDATION_FAILED',   '验证失败',   '验证未通过',                           7, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b301c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'VALIDATION_FAILED');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fc02a001-0001-4000-a002-000000000008', 'b301c3d4-5678-9abc-def0-1234567890ab', 'PR_READY',            'PR就绪',     '可创建 Pull Request',                  8, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b301c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'PR_READY');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fc02a001-0001-4000-a002-000000000009', 'b301c3d4-5678-9abc-def0-1234567890ab', 'PR_CREATED',          'PR已创建',   'Pull Request 已创建',                  9, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b301c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'PR_CREATED');

-- ==================== 字典类型 — graph_node_type ====================

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'c401c3d4-5678-9abc-def0-1234567890ab', 'graph_node_type',  '图谱节点类型', '知识图谱中节点的类型分类',               18, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'graph_node_type');

-- ==================== 字典项 — graph_node_type ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd01a001-0001-4000-a003-000000000001', 'c401c3d4-5678-9abc-def0-1234567890ab', 'BusinessDomain',   '业务域',     '业务领域划分',                        1, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c401c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'BusinessDomain');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd01a001-0001-4000-a003-000000000002', 'c401c3d4-5678-9abc-def0-1234567890ab', 'BusinessProcess',  '业务流程',   '业务处理流程节点',                    2, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c401c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'BusinessProcess');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd01a001-0001-4000-a003-000000000003', 'c401c3d4-5678-9abc-def0-1234567890ab', 'FeatureModule',    '功能模块',   '功能模块划分节点',                    3, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c401c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'FeatureModule');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd01a001-0001-4000-a003-000000000004', 'c401c3d4-5678-9abc-def0-1234567890ab', 'Feature',          '功能点',     '具体功能点节点',                      4, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c401c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'Feature');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd01a001-0001-4000-a003-000000000005', 'c401c3d4-5678-9abc-def0-1234567890ab', 'ApiEndpoint',      'API接口',    'API 端点节点',                        5, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c401c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'ApiEndpoint');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd01a001-0001-4000-a003-000000000006', 'c401c3d4-5678-9abc-def0-1234567890ab', 'Controller',       'Controller', 'Spring MVC 控制器节点',               6, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c401c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'Controller');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd01a001-0001-4000-a003-000000000007', 'c401c3d4-5678-9abc-def0-1234567890ab', 'Service',          'Service',    '业务服务层节点',                      7, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c401c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'Service');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd01a001-0001-4000-a003-000000000008', 'c401c3d4-5678-9abc-def0-1234567890ab', 'Mapper',           'Mapper',     '数据访问层节点',                      8, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c401c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'Mapper');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd01a001-0001-4000-a003-000000000009', 'c401c3d4-5678-9abc-def0-1234567890ab', 'SqlStatement',     'SQL语句',    'SQL 语句节点',                        9, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c401c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'SqlStatement');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd01a001-0001-4000-a003-000000000010', 'c401c3d4-5678-9abc-def0-1234567890ab', 'Table',            '数据库表',   '数据库表节点',                       10, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c401c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'Table');

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd01a001-0001-4000-a003-000000000011', 'c401c3d4-5678-9abc-def0-1234567890ab', 'Column',           '字段',       '数据库字段节点',                      11, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c401c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'Column');
