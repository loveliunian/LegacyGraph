-- ============================================
-- V66: 方案增强 - 成本/风险/备选方案 + 变更任务桥接 + 代码片段持久化
--
-- G7: lg_solution 新增 estimated_cost_json / risk_assessment_json
-- G8: lg_solution 新增 change_task_id（桥接 ChangeTask）
-- G6 补充: lg_solution_step 新增 code_snippet / code_language（P0 遗漏的 DB 列）
-- ============================================

-- 1. lg_solution 新增成本/风险/桥接字段
ALTER TABLE lg_solution ADD COLUMN IF NOT EXISTS estimated_cost_json   TEXT;
ALTER TABLE lg_solution ADD COLUMN IF NOT EXISTS risk_assessment_json  TEXT;
ALTER TABLE lg_solution ADD COLUMN IF NOT EXISTS change_task_id        UUID;

COMMENT ON COLUMN lg_solution.estimated_cost_json  IS '成本估算 JSON（SolutionPlan.estimatedCost 序列化）';
COMMENT ON COLUMN lg_solution.risk_assessment_json IS '风险评估 JSON（SolutionPlan.riskAssessment 序列化）';
COMMENT ON COLUMN lg_solution.change_task_id       IS '关联的变更任务 ID（方案转执行后填充）';

-- 2. lg_solution_step 新增代码片段字段（P0 G6 遗漏）
ALTER TABLE lg_solution_step ADD COLUMN IF NOT EXISTS code_snippet    TEXT;
ALTER TABLE lg_solution_step ADD COLUMN IF NOT EXISTS code_language   VARCHAR(32);

COMMENT ON COLUMN lg_solution_step.code_snippet  IS '代码片段（MODIFY 为修改后代码，CREATE 为新代码，DELETE 为空）';
COMMENT ON COLUMN lg_solution_step.code_language IS '代码语言（java/xml/sql/vue/ts 等）';
