-- ============================================
-- V43: 新增图谱关系类型字典（graph_edge_type）
--      与后端 io.github.legacygraph.common.EdgeType 枚举保持一致
--      供前端展示关系时通过 dictLabel('graph_edge_type', value) 转换为中文
-- ============================================

-- ==================== 字典类型 — graph_edge_type ====================

INSERT INTO lg_sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'd501c3d4-5678-9abc-def0-1234567890ab', 'graph_edge_type', '图谱关系类型', '知识图谱中边（关系）的类型分类', 19, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict WHERE dict_code = 'graph_edge_type');

-- ==================== 字典项 — graph_edge_type ====================

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000001', 'd501c3d4-5678-9abc-def0-1234567890ab', 'CONTAINS',            '包含',         '父节点包含子节点',                     1, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'CONTAINS');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000002', 'd501c3d4-5678-9abc-def0-1234567890ab', 'IMPLEMENTED_BY',      '由...实现',     '被某实现体实现',                       2, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'IMPLEMENTED_BY');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000003', 'd501c3d4-5678-9abc-def0-1234567890ab', 'IMPLEMENTS',          '实现',         '实现某接口或规范',                     3, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'IMPLEMENTS');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000004', 'd501c3d4-5678-9abc-def0-1234567890ab', 'USES',                '使用',         '使用另一节点',                         4, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'USES');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000005', 'd501c3d4-5678-9abc-def0-1234567890ab', 'HAS_RULE',            '拥有规则',     '拥有业务规则',                         5, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'HAS_RULE');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000006', 'd501c3d4-5678-9abc-def0-1234567890ab', 'EXPOSED_BY',          '由...暴露',     '被某接口暴露',                         6, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'EXPOSED_BY');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000007', 'd501c3d4-5678-9abc-def0-1234567890ab', 'REQUIRES_PERMISSION', '需要权限',     '需要特定权限',                         7, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'REQUIRES_PERMISSION');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000008', 'd501c3d4-5678-9abc-def0-1234567890ab', 'REQUIRES',            '需要',         '依赖前置条件',                         8, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'REQUIRES');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000009', 'd501c3d4-5678-9abc-def0-1234567890ab', 'CALLS',               '调用',         '调用另一节点',                         9, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'CALLS');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000010', 'd501c3d4-5678-9abc-def0-1234567890ab', 'HANDLED_BY',          '由...处理',     '被某处理器处理',                       10, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'HANDLED_BY');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000011', 'd501c3d4-5678-9abc-def0-1234567890ab', 'EXECUTES',            '执行',         '执行某操作',                           11, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'EXECUTES');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000012', 'd501c3d4-5678-9abc-def0-1234567890ab', 'READS',               '读取',         '读取数据',                             12, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'READS');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000013', 'd501c3d4-5678-9abc-def0-1234567890ab', 'WRITES',              '写入',         '写入数据',                             13, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'WRITES');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000014', 'd501c3d4-5678-9abc-def0-1234567890ab', 'HAS_COLUMN',          '拥有字段',     '数据表拥有字段',                       14, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'HAS_COLUMN');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000015', 'd501c3d4-5678-9abc-def0-1234567890ab', 'HAS_INDEX',           '拥有索引',     '数据表拥有索引',                       15, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'HAS_INDEX');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000016', 'd501c3d4-5678-9abc-def0-1234567890ab', 'UNIQUE_ON',           '唯一约束字段', '唯一约束涉及的字段',                   16, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'UNIQUE_ON');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000017', 'd501c3d4-5678-9abc-def0-1234567890ab', 'JOINS',               'JOIN',         'SQL JOIN 关联',                        17, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'JOINS');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000018', 'd501c3d4-5678-9abc-def0-1234567890ab', 'TRIGGERS',            '触发',         '触发某流程或事件',                     18, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'TRIGGERS');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000019', 'd501c3d4-5678-9abc-def0-1234567890ab', 'CONSUMES',            '消费',         '消费消息或资源',                       19, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'CONSUMES');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000020', 'd501c3d4-5678-9abc-def0-1234567890ab', 'CALLS_EXTERNAL',      '调用外部系统', '调用外部系统或服务',                   20, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'CALLS_EXTERNAL');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000021', 'd501c3d4-5678-9abc-def0-1234567890ab', 'VERIFIED_BY',         '由...验证',     '被某测试或断言验证',                   21, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'VERIFIED_BY');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000022', 'd501c3d4-5678-9abc-def0-1234567890ab', 'ASSERTS',             '断言',         '进行断言校验',                         22, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'ASSERTS');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000023', 'd501c3d4-5678-9abc-def0-1234567890ab', 'HAS_EVIDENCE',        '拥有证据',     '拥有支撑证据',                         23, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'HAS_EVIDENCE');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000024', 'd501c3d4-5678-9abc-def0-1234567890ab', 'REFERENCES',          '外键引用',     '外键引用另一表',                       24, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'REFERENCES');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000025', 'd501c3d4-5678-9abc-def0-1234567890ab', 'BELONGS_TO',          '属于',         '属于某父级',                           25, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'BELONGS_TO');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000026', 'd501c3d4-5678-9abc-def0-1234567890ab', 'MAPS_TO',             '对应',         '对应到另一节点',                       26, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'MAPS_TO');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000027', 'd501c3d4-5678-9abc-def0-1234567890ab', 'APPLIES_TO',          '应用于',       '应用于某目标',                         27, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'APPLIES_TO');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000028', 'd501c3d4-5678-9abc-def0-1234567890ab', 'AFFECTS',             '影响',         '变更影响某节点',                       28, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'AFFECTS');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000029', 'd501c3d4-5678-9abc-def0-1234567890ab', 'FIXED_BY',            '由...修复',     '被某变更任务修复',                     29, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'FIXED_BY');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000030', 'd501c3d4-5678-9abc-def0-1234567890ab', 'MIGRATES_TO',         '迁移到',       '迁移到某目标',                         30, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'MIGRATES_TO');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000031', 'd501c3d4-5678-9abc-def0-1234567890ab', 'DEPENDS_ON',          '依赖于',       '依赖另一节点',                         31, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'DEPENDS_ON');
