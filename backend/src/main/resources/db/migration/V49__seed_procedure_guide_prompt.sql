-- ============================================
-- V49: P5-23 新增 procedure-guide 提示词模板种子数据
-- --------------------------------------------
-- 背景：P5-19 新增 PROCEDURE_LOOKUP 意图（QueryIntent 枚举），
--   用于识别"如何操作/怎么办理/操作流程是什么/需要准备什么资料"类业务流程操作查询。
--   P5-23 配套该意图，新增"业务流程操作指南"提示词模板，输出结构含：
--     1. 业务流程概述
--     2. 操作步骤（角色/触发条件）
--     3. 所需资料清单（RequiredDocument）
--     4. 相关接口（ApiEndpoint，通过 IMPLEMENTS 关系关联）
--     5. 注意事项（severity + 建议）
--   占位符：
--     {question} 接收用户问题
--     {context}  接收检索上下文（业务流程 / 所需资料 / 相关接口 / 文档片段）
--   同步落地于 classpath:/prompts/procedure-guide.txt（DB 缺失时回退路径，
--   见 PromptTemplateLoader）。
-- 字段沿用 V12__seed_prompt_templates.sql / V48 的格式。
-- ============================================

INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'procedure-guide',
    '1.0',
    'business',
    '你是一位资深业务顾问。请基于检索到的业务流程图谱节点、所需资料、相关接口与文档片段，为用户给出一份清晰可执行的操作指南。',
    NULL,
    '## 用户问题
{question}

## 检索到的上下文（业务流程 / 所需资料 / 相关接口 / 文档片段）
{context}

## 输出要求
指南须包含以下章节，按顺序输出，缺失项填"（无）"并说明原因：

### 1. 业务流程概述
- 用一句话概括该业务的用途与适用场景。
- 如图谱中有 BusinessProcess 节点，引用其描述与步骤。

### 2. 操作步骤
- 按顺序列出用户需执行的操作步骤（steps）。
- 每步标注涉及的角色（谁能操作）与触发条件。
- 步骤来源优先取图谱 BusinessProcess.steps；缺失时从文档片段归纳并标注"（推断）"。

### 3. 所需资料清单
- 列出办理/操作时需要提交或准备的资料（RequiredDocument）。
- 每项标注是否必须（图谱无必填标记时默认"建议提供"）。
- 图谱无资料节点时从文档片段归纳，标注"（推断）"。

### 4. 相关接口
- 列出与该流程相关的后端接口（ApiEndpoint，通过 IMPLEMENTS 关系关联）。
- 每项给出：HTTP 方法 + 路径 + 业务语义（summary）+ 是否需要权限。
- 无接口证据时填"（无）"。

### 5. 注意事项
- 前置条件、状态流转限制、常见失败原因、权限要求。
- 每项给出 severity（HIGH/MEDIUM/LOW）与建议。

## 重要约束
- 只依据上下文推断已有流程与资料，不得编造上下文中不存在的步骤、资料、接口。
- 上下文不足以确定的部分，明确标注"（推断）"或"待确认"。
- 资料清单与步骤要具体可执行，避免空泛描述。',
    '{
  "summary": "业务流程一句话总览",
  "processName": "业务流程名称",
  "steps": [
    { "order": 1, "name": "步骤名", "role": "操作角色", "trigger": "触发条件", "note": "备注" }
  ],
  "requiredDocuments": [
    { "name": "资料名", "required": true, "note": "说明" }
  ],
  "apiEndpoints": [
    { "method": "POST", "path": "/api/orders", "summary": "创建订单", "requiresPermission": true }
  ],
  "notes": [
    { "severity": "MEDIUM", "description": "注意事项描述", "advice": "建议" }
  ]
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;
