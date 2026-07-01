-- ============================================
-- V8: 初始化数据字典（14 类 × 70 条）
--     repo_type / scan_type / scan_status / scan_stage
--     doc_type / test_case_type / test_case_status / risk_type / review_status
--     drift_type / evidence_type / db_status / repo_status / project_status
-- ============================================

-- ==================== 字典类型 ====================

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT '99278d6e-4269-5298-b77d-7f89c84ef86f', 'repo_type',       '仓库类型',       '代码仓库的类型分类：后端/前端/全栈',           1, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'repo_type');

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT '3dd5a665-e64c-5ff0-81fc-bb6b7ebe410e', 'scan_type',       '扫描类型',       '知识图谱扫描的任务类型',                     2, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'scan_type');

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0', 'scan_status',     '扫描状态',       '扫描任务的运行状态',                         3, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'scan_status');

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'ca72f61c-393a-59e8-ac45-b0cbf0512c63', 'scan_stage',      '扫描阶段',       '扫描任务当前所处的处理阶段',                  4, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'scan_stage');

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'c4fdecdf-1a63-5e94-8f76-cdbbfd40e409', 'doc_type',        '文档类型',       '项目文档的类型分类',                           5, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'doc_type');

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT '2d74553d-2393-5b67-96e5-2e54b13b7fed', 'test_case_type',  '测试用例类型',   '测试用例的分类',                              6, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'test_case_type');

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'a6bbdbfd-7667-5b2b-ae63-bf82bec4e156', 'test_case_status','测试用例状态',   '测试用例的生命周期状态',                       7, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'test_case_status');

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'b30c5baf-a40b-5eea-aafd-bfcebc83a778', 'risk_type',       '风险类型',       '迁移风险的类型分类',                           8, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'risk_type');

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'ac99f951-4355-5181-a511-e8e137cb43c9', 'review_status',   '审核状态',       '图谱节点/关系的人工审核结果状态',               9, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'review_status');

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'e2d2ad1b-a88d-5877-91be-4ca69825f2cc', 'drift_type',      '漂移类型',       '图谱与实际代码之间的漂移分类',                 10, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'drift_type');

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT '318b9a8f-a762-58b6-a8de-1413b5fdb2ff', 'evidence_type',   '证据类型',       '知识图谱证据的来源分类',                       11, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'evidence_type');

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'f5f4a256-4f5b-5001-b71b-ff0ab8b5a34d', 'db_status',       '数据库连接状态', '数据库连接测试的状态',                         12, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'db_status');

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT '44d844b3-466a-5f8f-a093-d8532d94231f', 'repo_status',     '代码仓库状态',   '代码仓库拉取/扫描的状态',                      13, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'repo_status');

INSERT INTO sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'a9257d3d-0d99-5418-bbc9-37a06f358f2b', 'project_status',  '项目状态',       '项目的生命周期状态',                            14, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'project_status');

-- ==================== 字典项 — repo_type ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '8ac8e8cd-1acc-5744-833c-b01f4347abea', '99278d6e-4269-5298-b77d-7f89c84ef86f', 'BACKEND',   '后端',  '后端服务代码仓库',               1, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '99278d6e-4269-5298-b77d-7f89c84ef86f' AND item_value = 'BACKEND');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '5c598a87-8d0e-5009-860d-e06c7ab99b82', '99278d6e-4269-5298-b77d-7f89c84ef86f', 'FRONTEND',  '前端',  '前端 UI 代码仓库',               2, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '99278d6e-4269-5298-b77d-7f89c84ef86f' AND item_value = 'FRONTEND');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '7125b39f-dd37-5095-ae69-395430f85714', '99278d6e-4269-5298-b77d-7f89c84ef86f', 'FULLSTACK', '全栈',  '同时包含前后端代码的仓库',         3, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '99278d6e-4269-5298-b77d-7f89c84ef86f' AND item_value = 'FULLSTACK');

-- ==================== 字典项 — scan_type ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'e35150dd-3b8a-5995-8743-cc40c1d1a43d', '3dd5a665-e64c-5ff0-81fc-bb6b7ebe410e', 'CODE_SCAN',     '代码扫描',   '解析 Controller、Service、Mapper、SQL 等', 1, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '3dd5a665-e64c-5ff0-81fc-bb6b7ebe410e' AND item_value = 'CODE_SCAN');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '5791cc62-e198-54f9-a727-d1d8797200a3', '3dd5a665-e64c-5ff0-81fc-bb6b7ebe410e', 'DB_SCAN',       '数据库扫描', '扫描数据库表结构、字段、索引、约束',        2, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '3dd5a665-e64c-5ff0-81fc-bb6b7ebe410e' AND item_value = 'DB_SCAN');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'a179c2d0-b4eb-585a-b7c4-6529a593201a', '3dd5a665-e64c-5ff0-81fc-bb6b7ebe410e', 'DOC_PARSE',     '文档解析',   '解析产品文档、API 文档',                   3, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '3dd5a665-e64c-5ff0-81fc-bb6b7ebe410e' AND item_value = 'DOC_PARSE');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '6841462d-735c-5d8f-9fe3-1cfb36f3b4f2', '3dd5a665-e64c-5ff0-81fc-bb6b7ebe410e', 'GRAPH_BUILD',   '图谱构建',   '基于事实构建知识图谱节点和关系',            4, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '3dd5a665-e64c-5ff0-81fc-bb6b7ebe410e' AND item_value = 'GRAPH_BUILD');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '9e8fbe7b-d1bb-5d65-b6af-a8ed3e6fbfd8', '3dd5a665-e64c-5ff0-81fc-bb6b7ebe410e', 'TEST_GENERATE', '测试生成',   '基于图谱和 API 自动生成接口测试用例',       5, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '3dd5a665-e64c-5ff0-81fc-bb6b7ebe410e' AND item_value = 'TEST_GENERATE');

-- ==================== 字典项 — scan_status ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'a59f6ebc-f7f0-5f89-b334-38f4b9436990', 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0', 'CREATED',    '已创建', '任务已创建，等待调度',    1, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0' AND item_value = 'CREATED');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '8a6a34b1-0ccf-549c-934d-6594247a6741', 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0', 'PENDING',    '等待中', '任务排队中',              2, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0' AND item_value = 'PENDING');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '5dfe7869-04a7-5fdc-878c-67df05d82d5a', 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0', 'RUNNING',    '运行中', '任务正在执行',            3, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0' AND item_value = 'RUNNING');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '9b254007-642e-5df0-81dd-642456ba893a', 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0', 'PROCESSING', '处理中', '任务正在处理',            4, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0' AND item_value = 'PROCESSING');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '66a9cbca-1bd2-5504-aa48-0c2dc05ab48f', 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0', 'SUCCESS',    '已完成', '任务执行成功',            5, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0' AND item_value = 'SUCCESS');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '05529b3e-d001-5b04-9510-813b816b3771', 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0', 'COMPLETED',  '已完成', '任务执行完成',            6, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0' AND item_value = 'COMPLETED');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'e331ee8b-158a-58f4-8652-8f98b6dd1d05', 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0', 'FAILED',     '失败',   '任务执行失败',            7, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0' AND item_value = 'FAILED');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'ddb09ad1-e7b3-5fd4-b3cc-043489eb708d', 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0', 'CANCELLED',  '已取消', '任务被取消',              8, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0' AND item_value = 'CANCELLED');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '064773aa-1908-507e-9d0e-a7fb095cd1af', 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0', 'PAUSED',     '已暂停', '任务已暂停',              9, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e493f5ac-ca55-5fc9-a2ba-96ad38a3f1d0' AND item_value = 'PAUSED');

-- ==================== 字典项 — scan_stage ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'c9bd0e8f-2878-528f-b279-1a38448ca08f', 'ca72f61c-393a-59e8-ac45-b0cbf0512c63', 'CODE_SCAN',     '代码扫描中',   '正在解析代码结构和调用关系',    1, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'ca72f61c-393a-59e8-ac45-b0cbf0512c63' AND item_value = 'CODE_SCAN');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '6164c337-d07b-5c09-8326-d042ae1e841a', 'ca72f61c-393a-59e8-ac45-b0cbf0512c63', 'DB_SCAN',       '数据库扫描中', '正在扫描数据库表结构',          2, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'ca72f61c-393a-59e8-ac45-b0cbf0512c63' AND item_value = 'DB_SCAN');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '6fad1c1c-1702-5749-aa3e-2e5a987081f3', 'ca72f61c-393a-59e8-ac45-b0cbf0512c63', 'DOC_PARSE',     '文档解析中',   '正在解析项目文档',              3, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'ca72f61c-393a-59e8-ac45-b0cbf0512c63' AND item_value = 'DOC_PARSE');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '8de2dd8a-38bf-5a96-a9a2-56b4f8490a86', 'ca72f61c-393a-59e8-ac45-b0cbf0512c63', 'GRAPH_BUILD',   '图谱构建中',   '正在构建知识图谱',              4, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'ca72f61c-393a-59e8-ac45-b0cbf0512c63' AND item_value = 'GRAPH_BUILD');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '78474232-1134-5d88-a606-38937e1b9d57', 'ca72f61c-393a-59e8-ac45-b0cbf0512c63', 'TEST_GENERATE', '测试生成中',   '正在生成测试用例',              5, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'ca72f61c-393a-59e8-ac45-b0cbf0512c63' AND item_value = 'TEST_GENERATE');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'eb1fed33-50ff-59eb-b1ea-528372f2807f', 'ca72f61c-393a-59e8-ac45-b0cbf0512c63', 'COMPLETED',     '已完成',       '所有扫描阶段执行完毕',          6, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'ca72f61c-393a-59e8-ac45-b0cbf0512c63' AND item_value = 'COMPLETED');

-- ==================== 字典项 — doc_type ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '391a374e-da15-53a6-8203-7b20a90e0d3c', 'c4fdecdf-1a63-5e94-8f76-cdbbfd40e409', 'PRODUCT',   '产品文档',   '产品需求与设计文档',      1, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c4fdecdf-1a63-5e94-8f76-cdbbfd40e409' AND item_value = 'PRODUCT');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'ba65a0c9-57ae-58b3-886a-42cdfb4827de', 'c4fdecdf-1a63-5e94-8f76-cdbbfd40e409', 'API',       '接口文档',   'API 接口规范文档',        2, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c4fdecdf-1a63-5e94-8f76-cdbbfd40e409' AND item_value = 'API');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '8a9f1c29-c458-5c31-95e9-7994c21cd2cb', 'c4fdecdf-1a63-5e94-8f76-cdbbfd40e409', 'MANUAL',    '操作手册',   '用户操作与运维手册',      3, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c4fdecdf-1a63-5e94-8f76-cdbbfd40e409' AND item_value = 'MANUAL');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '758bd343-8510-532c-ba0f-3759f71a7c67', 'c4fdecdf-1a63-5e94-8f76-cdbbfd40e409', 'DB_DESIGN', '数据库设计', '数据库表结构设计文档',    4, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c4fdecdf-1a63-5e94-8f76-cdbbfd40e409' AND item_value = 'DB_DESIGN');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '5736b618-9913-5f68-be82-f2064fe133d8', 'c4fdecdf-1a63-5e94-8f76-cdbbfd40e409', 'MIGRATION', '迁移文档',   '系统迁移相关文档',        5, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'c4fdecdf-1a63-5e94-8f76-cdbbfd40e409' AND item_value = 'MIGRATION');

-- ==================== 字典项 — test_case_type ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '1691a9a6-ae3f-57ec-841d-e90d0bb7b2e0', '2d74553d-2393-5b67-96e5-2e54b13b7fed', 'API',           'API测试',       '接口级测试用例',        1, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '2d74553d-2393-5b67-96e5-2e54b13b7fed' AND item_value = 'API');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'bf0d8df8-7205-5bd9-bfce-55854776986e', '2d74553d-2393-5b67-96e5-2e54b13b7fed', 'E2E',           '端到端测试',    '全链路端到端测试',      2, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '2d74553d-2393-5b67-96e5-2e54b13b7fed' AND item_value = 'E2E');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '17f7c644-ba54-5014-b473-63b0628b1365', '2d74553d-2393-5b67-96e5-2e54b13b7fed', 'DB_ASSERTION',  '数据库断言',    '数据库数据验证断言',    3, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '2d74553d-2393-5b67-96e5-2e54b13b7fed' AND item_value = 'DB_ASSERTION');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '585268e3-46f2-5ed3-bc0c-ecb0a0941b9d', '2d74553d-2393-5b67-96e5-2e54b13b7fed', 'BUSINESS_RULE', '业务规则测试',  '业务逻辑规则验证测试',  4, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '2d74553d-2393-5b67-96e5-2e54b13b7fed' AND item_value = 'BUSINESS_RULE');

-- ==================== 字典项 — test_case_status ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'e3ef8785-5c27-5aa5-8251-9f48a1bb7ab5', 'a6bbdbfd-7667-5b2b-ae63-bf82bec4e156', 'DRAFT',     '草稿',   '未确认的草稿用例',  1, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'a6bbdbfd-7667-5b2b-ae63-bf82bec4e156' AND item_value = 'DRAFT');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'b6a33bea-77b9-5926-9660-baa9bbde74b8', 'a6bbdbfd-7667-5b2b-ae63-bf82bec4e156', 'CONFIRMED', '已确认', '已验证通过的用例',  2, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'a6bbdbfd-7667-5b2b-ae63-bf82bec4e156' AND item_value = 'CONFIRMED');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '99b68a47-94f7-5d09-84af-eec38d34f74b', 'a6bbdbfd-7667-5b2b-ae63-bf82bec4e156', 'DISABLED',  '已禁用', '已停用的测试用例',  3, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'a6bbdbfd-7667-5b2b-ae63-bf82bec4e156' AND item_value = 'DISABLED');

-- ==================== 字典项 — risk_type ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '41c26577-7fe7-5799-84e2-786f61ae7d39', 'b30c5baf-a40b-5eea-aafd-bfcebc83a778', 'COMPLEX_CALL_CHAIN',   '复杂调用链', '方法调用链路过长或过于复杂',           1, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b30c5baf-a40b-5eea-aafd-bfcebc83a778' AND item_value = 'COMPLEX_CALL_CHAIN');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '3cb7dad1-8ee9-5c64-83f6-c09b5786b5f0', 'b30c5baf-a40b-5eea-aafd-bfcebc83a778', 'TABLE_COUPLING',        '表耦合',     '数据库表之间过度耦合',                 2, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b30c5baf-a40b-5eea-aafd-bfcebc83a778' AND item_value = 'TABLE_COUPLING');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '0aa466d8-1d14-50ef-b687-943ac6f6dea5', 'b30c5baf-a40b-5eea-aafd-bfcebc83a778', 'MISSING_DOC',           '文档缺失',   '缺少必要的技术文档',                    3, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b30c5baf-a40b-5eea-aafd-bfcebc83a778' AND item_value = 'MISSING_DOC');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '7646c593-1c58-536f-aeaa-395f2cf0d33f', 'b30c5baf-a40b-5eea-aafd-bfcebc83a778', 'MISSING_TEST',          '测试缺失',   '测试覆盖率不足',                        4, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b30c5baf-a40b-5eea-aafd-bfcebc83a778' AND item_value = 'MISSING_TEST');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'd266ab08-01cc-5fcf-b325-deefd41e640f', 'b30c5baf-a40b-5eea-aafd-bfcebc83a778', 'EXTERNAL_DEPENDENCY',   '外部依赖',   '对外部系统或服务的强依赖',                5, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b30c5baf-a40b-5eea-aafd-bfcebc83a778' AND item_value = 'EXTERNAL_DEPENDENCY');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '55a886ee-c908-50c0-a42c-2de3d351b1e8', 'b30c5baf-a40b-5eea-aafd-bfcebc83a778', 'LOW_CONFIDENCE',        '低置信度',   '图谱节点证据不足导致置信度过低',          6, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b30c5baf-a40b-5eea-aafd-bfcebc83a778' AND item_value = 'LOW_CONFIDENCE');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'e13c4a0e-2f2f-56fa-9b29-dfad5cba7ed8', 'b30c5baf-a40b-5eea-aafd-bfcebc83a778', 'UNCLEAR_LINEAGE',       '血缘不清',   '数据或调用血缘关系不清晰',               7, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b30c5baf-a40b-5eea-aafd-bfcebc83a778' AND item_value = 'UNCLEAR_LINEAGE');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'a6bd11cd-8827-5b78-85a9-0bebf74d5f4b', 'b30c5baf-a40b-5eea-aafd-bfcebc83a778', 'UNCLEAR_PERMISSION',    '权限不明',   '权限控制逻辑不明确',                    8, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'b30c5baf-a40b-5eea-aafd-bfcebc83a778' AND item_value = 'UNCLEAR_PERMISSION');

-- ==================== 字典项 — review_status ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'c1e13d4e-e478-55ff-9406-1c2ecfa1b2b7', 'ac99f951-4355-5181-a511-e8e137cb43c9', 'APPROVED',  '已通过', '审核通过',   1, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'ac99f951-4355-5181-a511-e8e137cb43c9' AND item_value = 'APPROVED');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '6436e55f-0c9d-5ba1-bf7c-158e35571257', 'ac99f951-4355-5181-a511-e8e137cb43c9', 'REJECTED',  '已拒绝', '审核不通过', 2, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'ac99f951-4355-5181-a511-e8e137cb43c9' AND item_value = 'REJECTED');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '40686478-f468-5ccd-9c2f-3cc1dfcfc927', 'ac99f951-4355-5181-a511-e8e137cb43c9', 'IGNORED',   '已忽略', '已忽略审核', 3, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'ac99f951-4355-5181-a511-e8e137cb43c9' AND item_value = 'IGNORED');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '80311bbd-3538-5fa4-8ef7-52e845070641', 'ac99f951-4355-5181-a511-e8e137cb43c9', 'CONFIRMED', '已确认', '已确认有效', 4, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'ac99f951-4355-5181-a511-e8e137cb43c9' AND item_value = 'CONFIRMED');

-- ==================== 字典项 — drift_type ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'c18a40c8-5d9f-59ef-b39b-f2b280ed600a', 'e2d2ad1b-a88d-5877-91be-4ca69825f2cc', 'static_only',   '仅静态',   '仅静态代码中存在',        1, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e2d2ad1b-a88d-5877-91be-4ca69825f2cc' AND item_value = 'static_only');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '00495157-5599-54bc-8c3c-99dcc88142a5', 'e2d2ad1b-a88d-5877-91be-4ca69825f2cc', 'dynamic_only',  '仅运行时', '仅运行时动态存在',        2, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e2d2ad1b-a88d-5877-91be-4ca69825f2cc' AND item_value = 'dynamic_only');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '5c93ef5f-5ee7-57a7-b712-50a35a9db209', 'e2d2ad1b-a88d-5877-91be-4ca69825f2cc', 'doc_only',      '仅文档',   '仅文档描述中存在',        3, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e2d2ad1b-a88d-5877-91be-4ca69825f2cc' AND item_value = 'doc_only');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '869d5b88-bab7-59aa-9dc6-2ca6c69ecb09', 'e2d2ad1b-a88d-5877-91be-4ca69825f2cc', 'low_confidence','低置信度',  '证据置信度过低',          4, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e2d2ad1b-a88d-5877-91be-4ca69825f2cc' AND item_value = 'low_confidence');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'b8bca6c5-77a2-5f59-9637-a31def9ebc3a', 'e2d2ad1b-a88d-5877-91be-4ca69825f2cc', 'test_failed',   '测试失败', '测试用例执行失败',        5, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'e2d2ad1b-a88d-5877-91be-4ca69825f2cc' AND item_value = 'test_failed');

-- ==================== 字典项 — evidence_type ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'aa09a557-1978-5763-a507-f3a1ecff1ee0', '318b9a8f-a762-58b6-a8de-1413b5fdb2ff', 'FILE_LINE',     '代码行',    '源代码文件的具体行',      1, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '318b9a8f-a762-58b6-a8de-1413b5fdb2ff' AND item_value = 'FILE_LINE');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'f990071c-570f-547b-8e7a-da817ae2af9b', '318b9a8f-a762-58b6-a8de-1413b5fdb2ff', 'SQL_STATEMENT', 'SQL语句',   '数据库 SQL 语句',         2, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '318b9a8f-a762-58b6-a8de-1413b5fdb2ff' AND item_value = 'SQL_STATEMENT');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '226c68b3-561d-5823-9b43-59dfa200d75f', '318b9a8f-a762-58b6-a8de-1413b5fdb2ff', 'DB_SCHEMA',     '数据库模式','数据库表结构定义',        3, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '318b9a8f-a762-58b6-a8de-1413b5fdb2ff' AND item_value = 'DB_SCHEMA');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'e8c6efe0-c617-5a77-a7e8-99b1b4820cbe', '318b9a8f-a762-58b6-a8de-1413b5fdb2ff', 'DOC_PARAGRAPH', '文档段落',  '产品/技术文档段落',      4, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '318b9a8f-a762-58b6-a8de-1413b5fdb2ff' AND item_value = 'DOC_PARAGRAPH');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '4139c79d-2eef-50d6-8ed9-62136e90b44a', '318b9a8f-a762-58b6-a8de-1413b5fdb2ff', 'API_DOC',       'API文档',   '接口规范文档',            5, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '318b9a8f-a762-58b6-a8de-1413b5fdb2ff' AND item_value = 'API_DOC');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'c4230848-c30e-5c5e-a77a-83669ba26990', '318b9a8f-a762-58b6-a8de-1413b5fdb2ff', 'TEST_RESULT',   '测试结果',  '测试执行结果',            6, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '318b9a8f-a762-58b6-a8de-1413b5fdb2ff' AND item_value = 'TEST_RESULT');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'e880022e-bc61-54a0-92e1-8dd12c136cd5', '318b9a8f-a762-58b6-a8de-1413b5fdb2ff', 'AI_REASONING',  'AI推理',    '大语言模型推理输出',      7, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '318b9a8f-a762-58b6-a8de-1413b5fdb2ff' AND item_value = 'AI_REASONING');

-- ==================== 字典项 — db_status ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '3017963b-7fb3-5d77-ad79-87282c95f6ed', 'f5f4a256-4f5b-5001-b71b-ff0ab8b5a34d', 'SUCCESS', '连接成功', '数据库连接测试通过',  1, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'f5f4a256-4f5b-5001-b71b-ff0ab8b5a34d' AND item_value = 'SUCCESS');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '8024b8bc-78e3-58b8-82f8-4fef5c71c1fb', 'f5f4a256-4f5b-5001-b71b-ff0ab8b5a34d', 'FAILED',  '连接失败', '数据库连接测试失败',  2, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'f5f4a256-4f5b-5001-b71b-ff0ab8b5a34d' AND item_value = 'FAILED');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '14597d00-be64-5a8a-9bd6-3b4f095792f3', 'f5f4a256-4f5b-5001-b71b-ff0ab8b5a34d', 'UNKNOWN', '未知',     '数据库连接状态未知',  3, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'f5f4a256-4f5b-5001-b71b-ff0ab8b5a34d' AND item_value = 'UNKNOWN');

-- ==================== 字典项 — repo_status ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fadd99bc-483b-543f-969e-4b878981b010', '44d844b3-466a-5f8f-a093-d8532d94231f', 'READY',    '就绪',   '仓库已拉取就绪',      1, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '44d844b3-466a-5f8f-a093-d8532d94231f' AND item_value = 'READY');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '76f5a3ca-a8db-5437-ad81-dcc4bda259cb', '44d844b3-466a-5f8f-a093-d8532d94231f', 'PULLING',  '拉取中', '正在拉取代码',        2, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '44d844b3-466a-5f8f-a093-d8532d94231f' AND item_value = 'PULLING');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '1073b362-77c4-564c-abcd-feccec05f629', '44d844b3-466a-5f8f-a093-d8532d94231f', 'FAILED',   '失败',   '拉取或扫描失败',      3, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '44d844b3-466a-5f8f-a093-d8532d94231f' AND item_value = 'FAILED');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'b36339a8-0d46-5541-95a9-00600583adb3', '44d844b3-466a-5f8f-a093-d8532d94231f', 'INIT',     '初始化', '仓库初始状态',        4, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '44d844b3-466a-5f8f-a093-d8532d94231f' AND item_value = 'INIT');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '2919a2b6-7fa3-52f8-b5b6-19d2f151d994', '44d844b3-466a-5f8f-a093-d8532d94231f', 'SCANNING', '扫描中', '正在扫描代码',        5, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = '44d844b3-466a-5f8f-a093-d8532d94231f' AND item_value = 'SCANNING');

-- ==================== 字典项 — project_status ====================

INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'e5bd257a-19a3-59d9-9abc-59dc215f7c2c', 'a9257d3d-0d99-5418-bbc9-37a06f358f2b', 'ACTIVE',    '活跃',   '项目正常运行',      1, true,  'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'a9257d3d-0d99-5418-bbc9-37a06f358f2b' AND item_value = 'ACTIVE');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '7889d709-cfd9-5405-aa21-6bf0ba505e98', 'a9257d3d-0d99-5418-bbc9-37a06f358f2b', 'INACTIVE',  '停用',   '项目已停用',        2, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'a9257d3d-0d99-5418-bbc9-37a06f358f2b' AND item_value = 'INACTIVE');
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT '2031cc4a-bb87-5cc0-803e-2a15676aab15', 'a9257d3d-0d99-5418-bbc9-37a06f358f2b', 'ARCHIVED',  '已归档', '项目已归档',        3, false, 'ACTIVE', NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = 'a9257d3d-0d99-5418-bbc9-37a06f358f2b' AND item_value = 'ARCHIVED');
