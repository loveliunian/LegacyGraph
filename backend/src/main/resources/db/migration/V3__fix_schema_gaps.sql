-- ============================================
-- V3: 修复 lg_code_repo 缺少的列 + lg_reports version_id 改为可空
-- ============================================

-- 1. lg_code_repo 添加后端子路径和前端子路径字段
ALTER TABLE lg_code_repo ADD COLUMN IF NOT EXISTS backend_sub_path VARCHAR(512);
ALTER TABLE lg_code_repo ADD COLUMN IF NOT EXISTS frontend_sub_path VARCHAR(512);

COMMENT ON COLUMN lg_code_repo.backend_sub_path IS '全栈项目-后端子路径';
COMMENT ON COLUMN lg_code_repo.frontend_sub_path IS '全栈项目-前端子路径';

-- 2. lg_reports 报告不强制绑定扫描版本（项目级报告无需 version_id）
ALTER TABLE lg_reports ALTER COLUMN version_id DROP NOT NULL;
