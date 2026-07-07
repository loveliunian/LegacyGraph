-- ============================================
-- V40: 新增 ADD_COLUMN 变更链路 Prompt 模板 + 更新 intent-classifier
-- 对应 doc/项目升级计划/QA变更影响问答打通详细设计.md §4.1.2 / §4.3.3
-- ============================================

-- 1. add-column-patch: ADD_COLUMN 执行计划模板
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'add-column-patch',
    '1.0',
    'change',
    '你是资深后端架构师。基于表结构变更与影响子图，生成 ADD_COLUMN 执行计划。',
    NULL,
    '## 变更信息
- 表: {tableName}
- 新字段: {columnName} {columnType}  nullable={nullable} default={defaultValue}
- 受影响文件: {impactedFiles}

## 输出要求（严格 JSON，对齐 PatchPlan 契约）
1. patches: 至少包含 Flyway 迁移脚本（ALTER TABLE ADD COLUMN）、实体类改动、Mapper XML 改动、Service/Controller DTO 改动（按影响子图，无证据的文件不得编造）
2. impactedFiles: 仅列影响子图内的文件，每项附 reason
3. validationGates: ["STATIC","UNIT","DB","MIGRATION"]
4. newTests: DB 断言（字段存在 + 默认值）+ 实体字段单测
5. riskLevel: BREAKING → HIGH，新增可空字段 → LOW
6. manualReviewNeeded: 含 NOT NULL 无默认值 / 涉及唯一索引时 true

只输出 JSON：{"taskType":"ADD_COLUMN","riskLevel":"LOW","impactedFiles":[...],"patches":[...],"newTests":[...],"validationGates":[...],"manualReviewNeeded":false,"generatedBy":"add-column"}',
    '{
  "taskType": "ADD_COLUMN",
  "riskLevel": "LOW",
  "patches": [{"filePath":"...","changeType":"CREATE","patchText":"...","evidenceIds":[]}],
  "manualReviewNeeded": false
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- 2. add-column-parse: 变更请求解析模板（供 ChangeImpactQuestionParser 未来 LLM 增强使用）
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'add-column-parse',
    '1.0',
    'change',
    '你是变更请求解析器。从用户问题中抽取结构化变更信息。',
    NULL,
    '## 用户问题
{question}

判断 changeKind（ADD_COLUMN / MODIFY_COLUMN / ADD_API / REFACTOR / UNKNOWN），抽取 tableName、columnName、columnType。
仅输出 JSON：{"changeKind":"ADD_COLUMN","tableName":"lg_change_task","columnName":"priority","columnType":"VARCHAR(32)"}',
    '{
  "changeKind": "ADD_COLUMN",
  "tableName": "",
  "columnName": "",
  "columnType": ""
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- 3. 更新 intent-classifier：追加 CHANGE_IMPACT 规则（V36 已 seed，用 REPLACE 追加）
UPDATE lg_prompt_template
SET task_prompt = REPLACE(
        task_prompt,
        '- EXPLANATION: 需要解释原因、设计决策',
        '- EXPLANATION: 需要解释原因、设计决策' || CHR(10) ||
        '- CHANGE_IMPACT: 涉及变更如何执行（加字段/改表/加列/删字段/加接口/改接口/重构怎么做/需要改哪些地方）。这类问题不是查询现状，而是询问变更如何落地。关键词：加、增、改、删、新增、修改、怎么改、需要做哪些改动'
    )
WHERE template_code = 'intent-classifier'
  AND task_prompt NOT LIKE '%CHANGE_IMPACT%';
