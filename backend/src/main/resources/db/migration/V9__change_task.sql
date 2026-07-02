-- ============================================
-- V9: ChangeTask 变更闭环（增强版2）
-- 变更任务 / 补丁文件 / 验证门禁 / PR 任务
-- 见 doc §ChangeTask 落地模块、§PatchPlan 输出契约、§验证门禁与图谱回写
-- ============================================

-- 1. 变更任务表
CREATE TABLE IF NOT EXISTS lg_change_task (
    id                  UUID PRIMARY KEY,
    project_id          UUID NOT NULL,
    version_id          UUID,
    task_type           VARCHAR(32)  NOT NULL,          -- BUGFIX / REFACTOR / UPGRADE
    title               VARCHAR(255) NOT NULL,
    input_issue         JSONB,
    impacted_subgraph   JSONB,
    proposal            JSONB,
    risk_level          VARCHAR(16),                    -- LOW / MEDIUM / HIGH
    status              VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    agent_run_id        VARCHAR(64),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_lg_change_task_project ON lg_change_task(project_id);
CREATE INDEX IF NOT EXISTS idx_lg_change_task_status  ON lg_change_task(status);
COMMENT ON TABLE lg_change_task IS '变更任务 - bugfix/refactor/upgrade 受控执行的状态机载体';

-- 2. 补丁文件表
CREATE TABLE IF NOT EXISTS lg_patch_file (
    id                  UUID PRIMARY KEY,
    change_task_id      UUID NOT NULL REFERENCES lg_change_task(id),
    file_path           TEXT         NOT NULL,
    change_type         VARCHAR(32)  NOT NULL,          -- CREATE / MODIFY / DELETE
    before_sha          VARCHAR(64),
    after_sha           VARCHAR(64),
    patch_text          TEXT         NOT NULL,
    generated_by        VARCHAR(64)  NOT NULL,
    evidence_ids        JSONB,
    status              VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_lg_patch_file_task ON lg_patch_file(change_task_id);
COMMENT ON TABLE lg_patch_file IS '补丁文件 - unified diff，落盘前需过范围/格式/证据三类校验';

-- 3. 验证门禁表
CREATE TABLE IF NOT EXISTS lg_validation_gate (
    id                  UUID PRIMARY KEY,
    change_task_id      UUID NOT NULL REFERENCES lg_change_task(id),
    gate_type           VARCHAR(32)  NOT NULL,          -- STATIC / UNIT / API / DB / E2E / MIGRATION
    command             TEXT,
    result              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    report_uri          TEXT,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_lg_validation_gate_task ON lg_validation_gate(change_task_id);
COMMENT ON TABLE lg_validation_gate IS '验证门禁 - 复用测试执行结果，不另造测试结果表';

-- 4. PR 任务表
CREATE TABLE IF NOT EXISTS lg_pr_task (
    id                  UUID PRIMARY KEY,
    change_task_id      UUID NOT NULL REFERENCES lg_change_task(id),
    branch_name         VARCHAR(255) NOT NULL,
    pr_url              TEXT,
    pr_status           VARCHAR(32)  NOT NULL DEFAULT 'NOT_CREATED',
    reviewer_policy     JSONB,
    rollback_plan       JSONB,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_lg_pr_task_task ON lg_pr_task(change_task_id);
COMMENT ON TABLE lg_pr_task IS 'PR 任务 - AI 只创建 feature branch，未过门禁不能建 PR';

-- V9: 修复 DB 模板 task_prompt 缺少变量占位符，导致 LLM 收不到实际数据无法产出结论
-- 详见 PromptTemplateLoader.renderFromDb：task_prompt 不含 {var} 占位符时 replaceVariables 无效
-- 本次修复同时在 renderFromDb 中增加安全网（自动追加未替换变量），但仍更新 DB 模板以保证 Prompt 格式规范

-- 1. doc-understanding：补充 {docContent} 和 {sourcePath}
UPDATE lg_prompt_template
SET task_prompt = '## 文档信息
- 来源路径: {sourcePath}
- 文档内容:
```
{docContent}
```

## 抽取要求
1. 业务域(businessDomains)：大的业务领域
2. 业务流程(businessProcesses)：完整流程，含步骤、角色、对象、规则
3. 业务对象(businessObjects)：核心实体及属性
4. 业务规则(businessRules)：明确规则或约束
5. 角色(roles)：参与角色名称
6. 状态流转(statusTransitions)：对象状态变化
7. 功能清单(features)：可识别功能名称

## 约束
- 每条结论附 evidence 引用原文片段
- confidence 0~1，不确定不要硬编'
WHERE template_code = 'doc-understanding'
  AND task_prompt NOT LIKE '%{docContent}%';

-- 2. code-fact-extraction：补充 {codeContent} 和 {sourcePath}
UPDATE lg_prompt_template
SET task_prompt = '## 代码信息
- 源文件路径: {sourcePath}
- 代码内容:
```
{codeContent}
```

## 抽取要求
1. 业务功能：方法/片段实现什么业务
2. 输入输出：业务含义
3. 数据库操作：读写哪些表
4. 业务规则：重要判断条件
5. 依赖调用：调用哪些服务

## 约束
- 每条结论附代码证据（sourceUri=源文件路径，lineStart/lineEnd 标注行号）
- confidence 0~1，证据充分则高'
WHERE template_code = 'code-fact-extraction'
  AND task_prompt NOT LIKE '%{codeContent}%';

-- 3. feature-mapping：补充 {vueCode} {apiDefinitions} {controllerCode} {permissionInfo} {productDoc}
UPDATE lg_prompt_template
SET task_prompt = '## 前端页面组件代码
```
{vueCode}
```

## 前端 API 调用定义
```
{apiDefinitions}
```

## 后端 Controller 接口
```
{controllerCode}
```

## 权限注解信息
```
{permissionInfo}
```

## 产品文档功能清单
```
{productDoc}
```

## 任务要求
将上述输入对齐为映射条目：
- 每条关联：页面(pageKey)、按钮(buttonName)、API(apiKey)、业务动作(businessAction)、权限(permissionKey)
- 给出 confidence（0~1）和证据(evidence)
- 存在歧义或冲突记录在 conflicts
- 无法匹配的放入 unmatched'
WHERE template_code = 'feature-mapping'
  AND task_prompt NOT LIKE '%{vueCode}%';

-- 4. test-case-generation：补充功能节点和接口相关信息
UPDATE lg_prompt_template
SET task_prompt = '## 功能节点信息
- 功能标识: {featureKey}
- 功能名称: {featureName}

## API 接口定义
- 端点: {httpMethod} {apiEndpoint}
- 请求 Schema: {requestSchema}

## 数据库相关
- 关联表: {relatedTables}

## 业务规则
{businessRules}

## 生成要求
请生成完整测试用例，覆盖：
- 正常场景
- 权限场景
- 状态非法场景
- 数据不存在场景
- 边界场景
对不确定的数据 mark needHumanInput'
WHERE template_code = 'test-case-generation'
  AND task_prompt NOT LIKE '%{featureKey}%';

-- 5. graph-merge-decision：补充候选节点信息
UPDATE lg_prompt_template
SET task_prompt = '## 候选节点 A
- Key: {candidateAKey}
- 信息: {candidateAInfo}

## 候选节点 B
- Key: {candidateBKey}
- 信息: {candidateBInfo}

## 相似度得分
- 名称相似度: {nameScore}
- 语义相似度: {semanticScore}
- 结构重叠度: {structScore}
- 邻居相似度: {neighborScore}
- 证据重叠度: {evidenceScore}

## 任务要求
判断两个节点是否应合并：
- AUTO_MERGE：自动合并
- REVIEW：需人工审核
- REJECT：拒绝合并
给出理由和证据'
WHERE template_code = 'graph-merge-decision'
  AND task_prompt NOT LIKE '%{candidateAKey}%';

