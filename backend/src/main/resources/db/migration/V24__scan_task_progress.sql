-- V24: 扫描任务增加进度追踪字段
-- 为 lg_scan_task 表添加 total_items / processed_items / current_item
-- 让前端版本详情页可以展示每个扫描环节的详细进度

ALTER TABLE IF EXISTS lg_scan_task
    ADD COLUMN IF NOT EXISTS total_items    INTEGER,
    ADD COLUMN IF NOT EXISTS processed_items INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS current_item   VARCHAR(1024);
