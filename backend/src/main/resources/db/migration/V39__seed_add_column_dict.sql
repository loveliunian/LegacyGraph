-- ============================================
-- V39: 新增 ADD_COLUMN 变更任务类型字典项
-- 对应 doc/项目升级计划/QA变更影响问答打通详细设计.md §4.3.1
-- ============================================

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fc01a001-0001-4000-a002-000000000004',
       'b201c3d4-5678-9abc-def0-1234567890ab',  -- change_task_type dict_id（同 V25）
       'ADD_COLUMN', '加字段', '新增表字段（schema 变更）', 4, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM lg_sys_dict_item
    WHERE dict_id = 'b201c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'ADD_COLUMN'
);
