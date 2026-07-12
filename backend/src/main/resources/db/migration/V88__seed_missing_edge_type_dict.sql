-- ============================================
-- V88: 补齐 graph_edge_type 字典缺失项（H01）
--      V43 只 seed 了 31 项，EdgeType 枚举现有 59 项
--      本 migration 补齐 28 项缺失字典（含新增 IN_DOMAIN）
--      与后端 io.github.legacygraph.common.EdgeType 枚举保持一致
-- ============================================

-- ==================== 补齐字典项 — graph_edge_type ====================

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000032', 'd501c3d4-5678-9abc-def0-1234567890ab', 'POSSIBLE_SAME_AS',      '疑似同义',     '跨语言实体疑似同义',                   32, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'POSSIBLE_SAME_AS');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000059', 'd501c3d4-5678-9abc-def0-1234567890ab', 'EXTENDS',               '继承',         '继承某父类',                           59, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'EXTENDS');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000033', 'd501c3d4-5678-9abc-def0-1234567890ab', 'GRANTS',                '授予',         '角色被授予某权限',                     33, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'GRANTS');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000034', 'd501c3d4-5678-9abc-def0-1234567890ab', 'ASSIGNED_TO',           '分配给',       '角色被分配给某用户',                   34, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'ASSIGNED_TO');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000035', 'd501c3d4-5678-9abc-def0-1234567890ab', 'DATA_FLOW',             '数据流',       '数据流向',                             35, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'DATA_FLOW');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000036', 'd501c3d4-5678-9abc-def0-1234567890ab', 'REQUIRES_DOCUMENT',     '需要文档',     '需要某文档',                           36, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'REQUIRES_DOCUMENT');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000037', 'd501c3d4-5678-9abc-def0-1234567890ab', 'CALLS_DB',              '调用数据库',   '调用数据库操作',                       37, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'CALLS_DB');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000038', 'd501c3d4-5678-9abc-def0-1234567890ab', 'READS_DB',              '读取数据库',   '读取数据库表',                         38, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'READS_DB');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000039', 'd501c3d4-5678-9abc-def0-1234567890ab', 'WRITES_DB',             '写入数据库',   '写入数据库表',                         39, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'WRITES_DB');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000040', 'd501c3d4-5678-9abc-def0-1234567890ab', 'WRITES_LOG',            '写日志',       '写入日志',                             40, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'WRITES_LOG');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000041', 'd501c3d4-5678-9abc-def0-1234567890ab', 'READS_CONFIG',          '读配置',       '读取配置',                             41, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'READS_CONFIG');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000042', 'd501c3d4-5678-9abc-def0-1234567890ab', 'WRITES_CONFIG',         '写配置',       '写入配置',                             42, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'WRITES_CONFIG');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000043', 'd501c3d4-5678-9abc-def0-1234567890ab', 'EXPOSES_ENDPOINT',      '暴露接口',     '暴露 API 端点',                        43, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'EXPOSES_ENDPOINT');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000044', 'd501c3d4-5678-9abc-def0-1234567890ab', 'AUTHENTICATES_BY',      '认证方式',     '通过某方式认证',                       44, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'AUTHENTICATES_BY');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000045', 'd501c3d4-5678-9abc-def0-1234567890ab', 'AUTHORIZES_BY',         '授权方式',     '通过某方式授权',                       45, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'AUTHORIZES_BY');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000046', 'd501c3d4-5678-9abc-def0-1234567890ab', 'IN_DOMAIN',             '属于业务域',   'BusinessProcess 归类到 BusinessDomain', 46, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'IN_DOMAIN');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000047', 'd501c3d4-5678-9abc-def0-1234567890ab', 'HAS_ITEM',              '拥有条目',     '需求拥有条目',                         47, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'HAS_ITEM');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000048', 'd501c3d4-5678-9abc-def0-1234567890ab', 'HAS_ACCEPTANCE_CRITERION', '拥有验收条件', '需求条目拥有验收条件',                 48, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'HAS_ACCEPTANCE_CRITERION');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000049', 'd501c3d4-5678-9abc-def0-1234567890ab', 'HAS_CONSTRAINT',        '拥有约束',     '需求条目拥有约束',                     49, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'HAS_CONSTRAINT');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000050', 'd501c3d4-5678-9abc-def0-1234567890ab', 'HAS_ASSUMPTION',        '拥有假设',     '需求条目拥有假设',                     50, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'HAS_ASSUMPTION');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000051', 'd501c3d4-5678-9abc-def0-1234567890ab', 'RAISES_QUESTION',       '提出问题',     '需求条目提出待解问题',                 51, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'RAISES_QUESTION');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000052', 'd501c3d4-5678-9abc-def0-1234567890ab', 'SATISFIES',             '满足',         '方案满足某需求',                       52, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'SATISFIES');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000053', 'd501c3d4-5678-9abc-def0-1234567890ab', 'DERIVED_FROM',          '派生自',       '实现步骤派生自某需求',                 53, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'DERIVED_FROM');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000054', 'd501c3d4-5678-9abc-def0-1234567890ab', 'VERIFIES',              '验证',         '验证步骤验证某验收条件',               54, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'VERIFIES');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000055', 'd501c3d4-5678-9abc-def0-1234567890ab', 'STEP_OF',               '步骤属于',     '步骤属于某方案',                       55, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'STEP_OF');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000056', 'd501c3d4-5678-9abc-def0-1234567890ab', 'VALIDATED_BY',          '由...校验',    '被某证据或门禁校验',                   56, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'VALIDATED_BY');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000057', 'd501c3d4-5678-9abc-def0-1234567890ab', 'REVISED_BY',            '由...修订',    '方案被某修订版本修订',                 57, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'REVISED_BY');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000058', 'd501c3d4-5678-9abc-def0-1234567890ab', 'BOUND_BY',              '绑定于',       '方法绑定于某事务范围',                 58, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'BOUND_BY');
