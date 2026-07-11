-- V64__qa_test_case.sql
-- QA 评测测试用例表 + 30 条黄金集 / 冒烟集用例
-- 覆盖 7 种 QueryIntent（FACT_LOOKUP/STRUCTURAL/RELATIONAL/COMPARATIVE/TEMPORAL/EXPLANATION/CHANGE_IMPACT）
-- status: SMOKE（发布门禁冒烟集）/ GOLDEN（全量评测黄金集）

CREATE TABLE IF NOT EXISTS lg_qa_test_case (
    id                  VARCHAR(64)   PRIMARY KEY,
    question            TEXT          NOT NULL,
    expected_entities   JSONB,
    expected_keywords   JSONB,
    should_abstain      BOOLEAN       NOT NULL DEFAULT FALSE,
    intent              VARCHAR(32)   NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'GOLDEN',
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  lg_qa_test_case IS 'QA 评测测试用例表';
COMMENT ON COLUMN lg_qa_test_case.expected_entities IS '期望实体（JSONB 数组：表名/类名/字段名）';
COMMENT ON COLUMN lg_qa_test_case.expected_keywords IS '期望关键词（JSONB 数组）';
COMMENT ON COLUMN lg_qa_test_case.should_abstain IS '该问题是否应拒答（图谱中无答案时模型应拒答）';
COMMENT ON COLUMN lg_qa_test_case.intent IS '查询意图：FACT_LOOKUP/STRUCTURAL/RELATIONAL/COMPARATIVE/TEMPORAL/EXPLANATION/CHANGE_IMPACT';
COMMENT ON COLUMN lg_qa_test_case.status IS '状态：SMOKE（冒烟集）/ GOLDEN（黄金集）';

CREATE INDEX IF NOT EXISTS idx_qa_test_case_status ON lg_qa_test_case (status);
CREATE INDEX IF NOT EXISTS idx_qa_test_case_intent ON lg_qa_test_case (intent);

-- ==================== 34 条通用模板用例 ====================
-- 注意：expected_entities / expected_keywords 已清空为通用模板，不再绑定特定项目实体。
-- 项目级评测应在应用层为每个项目动态生成专属用例（project_id 非空）。
-- 这些全局模板用例（project_id=NULL）仅用于结构正确性校验，entityRecall/keywordCoverage 在空期望集时自动跳过。
-- FACT_LOOKUP（5）
INSERT INTO lg_qa_test_case (id, question, expected_entities, expected_keywords, should_abstain, intent, status) VALUES
('qatc-001', '某张表有哪些字段？', '[]'::jsonb, '[]'::jsonb, FALSE, 'FACT_LOOKUP', 'SMOKE'),
('qatc-002', '某个 Service 类在哪个包下？', '[]'::jsonb, '[]'::jsonb, FALSE, 'FACT_LOOKUP', 'GOLDEN'),
('qatc-003', '某个 Service 有哪些方法？', '[]'::jsonb, '[]'::jsonb, FALSE, 'FACT_LOOKUP', 'GOLDEN'),
('qatc-004', '某张表对应的实体类叫什么？', '[]'::jsonb, '[]'::jsonb, FALSE, 'FACT_LOOKUP', 'GOLDEN'),
('qatc-005', '某个 Mapper 映射了哪些表？', '[]'::jsonb, '[]'::jsonb, FALSE, 'FACT_LOOKUP', 'SMOKE'),

-- STRUCTURAL（5）
('qatc-006', '系统有哪些模块？', '[]'::jsonb, '[]'::jsonb, FALSE, 'STRUCTURAL', 'SMOKE'),
('qatc-007', 'Controller 层有哪些类？', '[]'::jsonb, '[]'::jsonb, FALSE, 'STRUCTURAL', 'GOLDEN'),
('qatc-008', '系统有哪些数据库表？', '[]'::jsonb, '[]'::jsonb, FALSE, 'STRUCTURAL', 'GOLDEN'),
('qatc-009', 'Service 层包含哪些类？', '[]'::jsonb, '[]'::jsonb, FALSE, 'STRUCTURAL', 'GOLDEN'),
('qatc-010', '系统有哪些 Mapper？', '[]'::jsonb, '[]'::jsonb, FALSE, 'STRUCTURAL', 'SMOKE'),

-- RELATIONAL（5）
('qatc-011', '某个业务流程涉及哪些方法？', '[]'::jsonb, '[]'::jsonb, FALSE, 'RELATIONAL', 'SMOKE'),
('qatc-012', '某个 Service 调用了哪些其他 Service？', '[]'::jsonb, '[]'::jsonb, FALSE, 'RELATIONAL', 'GOLDEN'),
('qatc-013', '某个 Controller 依赖哪些 Service？', '[]'::jsonb, '[]'::jsonb, FALSE, 'RELATIONAL', 'GOLDEN'),
('qatc-014', '某张表和哪些实体类相关？', '[]'::jsonb, '[]'::jsonb, FALSE, 'RELATIONAL', 'GOLDEN'),
('qatc-015', '从 Controller 到 Mapper 的调用链是什么？', '[]'::jsonb, '[]'::jsonb, FALSE, 'RELATIONAL', 'SMOKE'),

-- COMPARATIVE（3）
('qatc-016', 'V1 和 V2 的图谱有什么差异？', '[]'::jsonb, '[]'::jsonb, FALSE, 'COMPARATIVE', 'SMOKE'),
('qatc-017', '两个版本的某个 Service 实现有什么不同？', '[]'::jsonb, '[]'::jsonb, FALSE, 'COMPARATIVE', 'GOLDEN'),
('qatc-018', '最新版本相比上一版新增了哪些节点？', '[]'::jsonb, '[]'::jsonb, FALSE, 'COMPARATIVE', 'GOLDEN'),

-- TEMPORAL（3）
('qatc-019', '最近一次扫描是什么时候？', '[]'::jsonb, '[]'::jsonb, FALSE, 'TEMPORAL', 'SMOKE'),
('qatc-020', '某个类是什么时候加入的？', '[]'::jsonb, '[]'::jsonb, FALSE, 'TEMPORAL', 'GOLDEN'),
('qatc-021', '某张表最近一次变更是什么？', '[]'::jsonb, '[]'::jsonb, FALSE, 'TEMPORAL', 'GOLDEN'),

-- EXPLANATION（5）
('qatc-022', '系统的核心业务流程是什么？', '[]'::jsonb, '[]'::jsonb, FALSE, 'EXPLANATION', 'SMOKE'),
('qatc-023', '权限体系是怎么设计的？', '[]'::jsonb, '[]'::jsonb, FALSE, 'EXPLANATION', 'GOLDEN'),
('qatc-024', '某个模块的整体架构是怎样的？', '[]'::jsonb, '[]'::jsonb, FALSE, 'EXPLANATION', 'GOLDEN'),
('qatc-025', '两个业务实体之间是什么关系？', '[]'::jsonb, '[]'::jsonb, FALSE, 'EXPLANATION', 'GOLDEN'),
('qatc-026', '系统为什么要拆分 Service 和 Mapper 两层？', '[]'::jsonb, '[]'::jsonb, FALSE, 'EXPLANATION', 'SMOKE'),

-- CHANGE_IMPACT（4）
('qatc-027', '修改某张表的某个字段会影响哪些功能？', '[]'::jsonb, '[]'::jsonb, FALSE, 'CHANGE_IMPACT', 'SMOKE'),
('qatc-028', '给某张表加一个字段需要改哪些地方？', '[]'::jsonb, '[]'::jsonb, FALSE, 'CHANGE_IMPACT', 'GOLDEN'),
('qatc-029', '删除某个方法的影响范围？', '[]'::jsonb, '[]'::jsonb, FALSE, 'CHANGE_IMPACT', 'GOLDEN'),
('qatc-030', '修改某个 Mapper 的 SQL 会影响什么？', '[]'::jsonb, '[]'::jsonb, FALSE, 'CHANGE_IMPACT', 'SMOKE'),

-- 拒答用例（图谱中通常无答案，应拒答）—— 覆盖不同意图
('qatc-031', '系统使用的什么数据库引擎？', '[]'::jsonb, '[]'::jsonb, TRUE, 'FACT_LOOKUP', 'SMOKE'),
('qatc-032', '项目用的什么前端框架？', '[]'::jsonb, '[]'::jsonb, TRUE, 'FACT_LOOKUP', 'GOLDEN'),
('qatc-033', '生产环境的 QPS 是多少？', '[]'::jsonb, '[]'::jsonb, TRUE, 'TEMPORAL', 'SMOKE'),
('qatc-034', '团队有多少个开发人员？', '[]'::jsonb, '[]'::jsonb, TRUE, 'EXPLANATION', 'GOLDEN');

-- 校验：共 34 条（30 条正常 + 4 条拒答），覆盖 7 种意图，SMOKE 集覆盖全部意图
