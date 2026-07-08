-- ============================================
-- V45: 新增报告状态字典（report_status）
--      与后端 io.github.legacygraph.entity.Report.status 字段保持一致
--      供前端展示状态时通过 dictLabel('report_status', value) 转换为中文
-- ============================================

-- ==================== 字典类型 ====================

INSERT INTO lg_sys_dict (id, dict_code, dict_name, description, sort_order, status, created_at, updated_at)
SELECT 'e601c3d4-5678-9abc-def0-1234567890ab', 'report_status', '报告状态', '报告生成的生命周期状态', 20, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict WHERE dict_code = 'report_status');

-- ==================== 字典项 ====================

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd03a001-0001-4000-a005-000000000001', 'e601c3d4-5678-9abc-def0-1234567890ab', 'GENERATING', '生成中', '报告正在生成中', 1, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'e601c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'GENERATING');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd03a001-0001-4000-a005-000000000002', 'e601c3d4-5678-9abc-def0-1234567890ab', 'COMPLETED',  '已完成', '报告已生成完成', 2, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'e601c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'COMPLETED');

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd03a001-0001-4000-a005-000000000003', 'e601c3d4-5678-9abc-def0-1234567890ab', 'FAILED',     '失败',   '报告生成失败',   3, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM lg_sys_dict_item WHERE dict_id = 'e601c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'FAILED');
