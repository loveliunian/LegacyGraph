-- V68__qa_test_case_project_scope.sql
-- 为 lg_qa_test_case 增加 project_id 列，支持项目级 QA 门禁
-- 现有 V64 用例（LegacyGraph 自身示例实体，如 lg_account、OrderMapper）project_id 保持 NULL，
-- 不再作为任意被扫描项目的门禁案例，避免误拦截或失真评测。

ALTER TABLE lg_qa_test_case ADD COLUMN IF NOT EXISTS project_id UUID;

COMMENT ON COLUMN lg_qa_test_case.project_id IS '所属项目 ID（NULL 表示全局/模板用例，不参与项目级门禁；非 NULL 表示绑定到特定项目）';

CREATE INDEX IF NOT EXISTS idx_qa_test_case_project_status ON lg_qa_test_case (project_id, status);
