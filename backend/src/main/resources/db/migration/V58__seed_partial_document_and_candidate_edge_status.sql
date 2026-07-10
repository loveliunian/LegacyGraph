-- Make partial document extraction and cross-language feature candidates visible to UI dictionaries.

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fe01a001-0001-4000-a001-000000000008', 'a1b2c3d4-5678-9abc-def0-1234567890ab',
       'PARTIAL', '部分解析', '仅部分文档分块抽取成功，保留可追溯事实并等待重试', 8, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM lg_sys_dict_item
    WHERE dict_id = 'a1b2c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'PARTIAL'
);

INSERT INTO lg_sys_dict_item (id, dict_id, item_value, item_label, description, sort_order, is_default, status, created_at, updated_at)
SELECT 'fd02a001-0001-4000-a004-000000000032', 'd501c3d4-5678-9abc-def0-1234567890ab',
       'POSSIBLE_SAME_AS', '疑似同义', 'AI 识别的跨语言同义候选，必须人工确认后才能合并节点', 32, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM lg_sys_dict_item
    WHERE dict_id = 'd501c3d4-5678-9abc-def0-1234567890ab' AND item_value = 'POSSIBLE_SAME_AS'
);
