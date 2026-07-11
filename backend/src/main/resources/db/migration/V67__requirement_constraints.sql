-- ============================================
-- V67: 需求约束与开放问题持久化（P0 修复）
--
-- 修复 constraints / openQuestions 未持久化导致方案可绕过人工确认的问题。
--   constraints      —— 逐条需求项的约束（per-item），存 lg_requirement_item
--   openQuestions    —— 整份需求的开放问题（global），存 lg_requirement
-- 两列均以 TEXT 存储 JSON 数组字符串（如 ["c1","c2"]）。
-- ============================================

-- 1. 需求条目表：新增 constraints_json（per-item 约束）
ALTER TABLE lg_requirement_item ADD COLUMN IF NOT EXISTS constraints_json TEXT;
COMMENT ON COLUMN lg_requirement_item.constraints_json IS '需求条目约束列表 JSON 数组字符串';

-- 2. 需求主表：新增 open_questions_json（全局开放问题）
ALTER TABLE lg_requirement ADD COLUMN IF NOT EXISTS open_questions_json TEXT;
COMMENT ON COLUMN lg_requirement.open_questions_json IS '需求开放问题列表 JSON 数组字符串（全局，属于整份需求）';
