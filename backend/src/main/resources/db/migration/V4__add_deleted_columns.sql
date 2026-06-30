-- ============================================
-- V4: 补全缺失的 deleted 逻辑删除列（实体有 @TableLogic 但 PG 列缺失）
-- ============================================

-- 1. 扫描任务表
ALTER TABLE lg_scan_task ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;

-- 2. 运行时调用链表
ALTER TABLE lg_runtime_trace ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;

-- 3. 测试用例表
ALTER TABLE lg_test_case ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;

-- 4. 测试运行表
ALTER TABLE lg_test_run ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
