-- V26: 修复运行库跳过 V24 后 lg_scan_task 缺少进度列的问题
-- 线上 flyway_schema_history 可能存在 V25 但不存在 V24，Flyway 默认不会回补低版本迁移。
-- 因此用新的 V26 幂等补齐列，避免 ScanTask.totalItems/processedItems/currentItem 写入失败。

ALTER TABLE IF EXISTS lg_scan_task
    ADD COLUMN IF NOT EXISTS total_items INTEGER,
    ADD COLUMN IF NOT EXISTS processed_items INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS current_item VARCHAR(1024);
