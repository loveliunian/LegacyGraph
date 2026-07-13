-- ============================================
-- V91: 方案步骤增强 - 变更范围与测试文件列表
--
-- 阶段二-2.1: 方案输出升级为文件级变更计划
--   lg_solution_step 新增 change_scope (FULL/PARTIAL)
--   lg_solution_step 新增 test_files (JSON 数组字符串)
-- ============================================

ALTER TABLE lg_solution_step ADD COLUMN IF NOT EXISTS change_scope  VARCHAR(32);
ALTER TABLE lg_solution_step ADD COLUMN IF NOT EXISTS test_files    TEXT;

COMMENT ON COLUMN lg_solution_step.change_scope IS '变更范围：FULL（完整替换）/ PARTIAL（部分修改）';
COMMENT ON COLUMN lg_solution_step.test_files   IS '需新增/修改的测试文件列表（JSON 数组字符串）';
