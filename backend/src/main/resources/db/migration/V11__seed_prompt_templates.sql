-- ============================================
-- V11: 将所有 classpath:/prompts/*.txt 提示词模板导入数据库
-- 优先从 DB 加载，DB 无匹配时回退到文件（见 PromptTemplateLoader）
-- ============================================

-- change-impact
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'change-impact',
    '1.0',
    'code',
    '你是一位资深架构师。请基于变更内容与图谱依赖，做语义级影响分析。',
    NULL,
    '## 变更信息
- 变更目标: {changeTarget}
- 变更描述/diff: {changeDescription}
- 图谱依赖节点: {dependencies}

## 分析要求
1. 判断修改类型 changeType：BUGFIX、FEATURE、BREAKING_CHANGE、REFACTOR。
2. 评估业务影响严重程度 severity：HIGH、MEDIUM、LOW。
3. 预测需要重跑的测试范围 affectedTests。
4. 建议重点回归范围 regressionScope。
5. 列出受影响的关键节点 impactedNodes（来自依赖节点，勿编造）。',
    '{
  "changeType": "BREAKING_CHANGE",
  "severity": "HIGH",
  "summary": "影响总结一句话",
  "impactedNodes": ["api:/order/create", "service:OrderService"],
  "affectedTests": ["下单接口用例"],
  "regressionScope": ["订单创建链路"]
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- code-review
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'code-review',
    '1.0',
    'code',
    '你是一位资深代码审查工程师，请审查以下代码片段，指出潜在问题并给出改进建议。',
    NULL,
    '## 代码信息
- 文件路径: {filePath}
- 类名: {className}
- 方法名: {methodName}

## 代码内容:
```
{codeContent}
```

## 审查要求
请从以下方面审查：
1. **代码异味**：是否存在设计问题、重复代码、复杂方法
2. **错误潜在**：是否可能存在 NPE、异常未处理、资源泄漏
3. **安全问题**：是否存在 SQL 注入、XSS、权限问题
4. **性能问题**：是否存在明显的性能改进空间
5 **改进建议**：给出具体的改进建议',
    '{
  "codeSmells": [
    { "type": "问题类型", "description": "问题描述", "severity": "low|medium|high" }
  ],
  "potentialBugs": [
    { "location": "位置描述", "description": "bug 描述", "severity": "low|medium|high" }
  ],
  "securityIssues": [
    { "type": "问题类型", "description": "描述", "severity": "low|medium|high" }
  ],
  "performanceIssues": [
    { "location": "位置描述", "description": "描述", "suggestion": "改进建议" }
  ],
  "overallSuggestions": [
    "整体改进建议"
  ],
  "overallScore": 8
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- db-schema-analysis
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'db-schema-analysis',
    '1.0',
    'db',
    '你是一位资深数据库架构师和业务分析师。请分析以下数据库表结构，从业务角度理解每张表的含义。',
    NULL,
    '## 数据库 Schema 信息
{schemaInfo}

## 分析要求

### 1. 表级分析
对每张表，推断：
- **businessLabel**: 业务中文名称（如 `t_order` → "订单表"）
- **businessDescription**: 业务描述（一句话说明该表的作用，如果原表已有注释则以此为基础增强）
- **domain**: 所属业务域（如：用户、订单、商品、权限、系统配置）
- **importance**: 重要程度（CORE=核心业务表 / SUPPORT=支撑表 / LOOKUP=字典/配置表）

### 2. 业务域
归纳出所有业务域，每个域包含：
- **name**: 域名
- **description**: 域说明
- **tables**: 属于该域的表名列表

### 3. 隐式关系
识别命名规则无法推断的跨表关系：
- 关注列名不包含 `_id` 后缀但仍然引用其他表的字段
- 关注通过中间表（如 `user_role`）形成的多对多关系
- 关注状态字段引用的字典/枚举表
- 每个关系给出 fromTable、toTable、relationType（ONE_TO_MANY / MANY_TO_MANY / REFERENCE）、description',
    '{
  "tables": [
    { "tableName": "t_user", "businessLabel": "用户表", "businessDescription": "存储系统用户基本信息...", "domain": "用户", "importance": "CORE" }
  ],
  "domains": [
    { "name": "用户", "description": "用户注册、登录、权限相关", "tables": ["t_user", "t_role", "t_user_role"] }
  ],
  "implicitRelations": [
    { "fromTable": "t_user_role", "toTable": "t_user", "relationType": "MANY_TO_MANY", "description": "用户-角色多对多关联" }
  ],
  "schemaSummary": "该数据库是一个电商系统的核心库，包含用户、订单、商品等6个业务域的23张表..."
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- migration-convert
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'migration-convert',
    '1.0',
    'migration',
    '你是一位 Spring Boot 迁移专家。请针对以下代码，按目标迁移规则给出转换建议与转换后代码。',
    NULL,
    '## 迁移信息
- 迁移方向: {migrationDirection}
- 源文件: {sourcePath}
- 代码内容:
```
{code}
```
- 自定义迁移规则（可选）: {customRules}

## 分析要求
识别并转换常见迁移点（存在才报）：
1. 过时注解（如 `@Autowired` 字段注入 → 构造注入）。
2. 包名迁移（`javax.*` → `jakarta.*`）。
3. 配置/API 变化。
4. 自定义规则匹配项。',
    '{
  "summary": "迁移要点一句话",
  "changes": [
    { "ruleType": "JAVAX_TO_JAKARTA", "before": "javax.persistence.Entity", "after": "jakarta.persistence.Entity", "reason": "Spring Boot 3 使用 jakarta" }
  ],
  "migratedCode": "转换后的代码",
  "manualReviewNeeded": ["需人工确认的点"]
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- patch-plan
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'patch-plan',
    '1.0',
    'code',
    '你是一位资深工程师。请基于给定的影响子图、证据与失败测试，为 bug 修复生成**可校验的补丁计划**。你的目标不是猜测，而是基于输入事实产出可应用、可验证的补丁。',
    NULL,
    '## 任务信息
- 任务标题: {title}
- 变更目标: {changeTarget}
- 问题描述: {inputIssue}

## 影响子图（范围白名单，补丁只能改动这些文件）
{dependencySummary}

允许改动的文件:
{impactedFiles}

## 证据
{evidenceSummary}

## 失败测试 / 复现
{failingTests}

## 硬约束
1. 只能改动“允许改动的文件”列表内的文件，越界文件不得出现在 patches 中。
2. 每个 patch 必须是 unified diff（含 `---`/`+++`/`@@` 标记），能干净应用。
3. 每个 patch 至少引用一个 evidenceId。
4. 无法确认修复点时，降低 riskLevel 并在 manualReviewNeeded=true。
5. 只输出 JSON，不要其他解释内容。',
    '{
  "taskType": "BUGFIX",
  "riskLevel": "MEDIUM",
  "impactedFiles": [
    { "path": "backend/src/main/java/.../Foo.java", "reason": "空指针分支未处理" }
  ],
  "patches": [
    {
      "filePath": "backend/src/main/java/.../Foo.java",
      "changeType": "MODIFY",
      "patchText": "--- a/.../Foo.java\n+++ b/.../Foo.java\n@@ -10,3 +10,4 @@\n-old\n+new\n",
      "evidenceIds": ["evd-code-001"]
    }
  ],
  "newTests": [
    { "type": "UNIT", "target": "FooTest", "purpose": "覆盖空值分支" }
  ],
  "validationGates": ["STATIC", "UNIT"],
  "manualReviewNeeded": false
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- pr-description
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'pr-description',
    '1.0',
    'code',
    '你是一位资深工程师。请根据 git diff 变更内容，按 Conventional Commits 规范生成提交信息与 PR 描述。',
    NULL,
    '## 变更信息
- 分支: {branch}
- 关联 issue: {issue}
- 变更 diff / 摘要:
```
{diff}
```

## 生成要求
1. commitMessage 遵循 Conventional Commits（feat/fix/refactor/docs/chore 等）。
2. prTitle 简明扼要。
3. prBody 概括变更目的、影响范围、测试要点。
4. 若提供了 issue，在 prBody 末尾关联。',
    '{
  "commitMessage": "feat(order): 支持订单批量取消",
  "prTitle": "订单批量取消功能",
  "prBody": "## 变更目的\n...\n## 影响范围\n...\n## 测试\n...",
  "changeType": "feat"
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- qa-answer
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'qa-answer',
    '1.0',
    'qa',
    '你是 LegacyGraph 平台的智能助手。请基于检索到的图谱节点与文档片段（上下文），用中文回答用户问题。',
    NULL,
    '## 用户问题
{question}

## 检索到的上下文（图谱节点 / 文档片段）
{context}

## 回答要求
1. 只能依据上下文作答；上下文不足以回答时，answer 说明"现有图谱信息不足以回答"，并降低 confidence。
2. 不得编造上下文中不存在的表名、接口、模块。
3. usedEvidence 必须引用真实出现在上下文中的来源标识（如节点 key 或片段编号）。
4. confidence 为 0~1 之间的小数，反映回答的可靠程度。
5. relatedNodeKeys 列出与答案最相关的节点 key。',
    '{
  "answer": "对问题的自然语言回答",
  "confidence": 0.82,
  "usedEvidence": ["node:api:/user/register", "chunk#2"],
  "relatedNodeKeys": ["api:/user/register", "table:t_user"]
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- refactor-suggestion
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'refactor-suggestion',
    '1.0',
    'code',
    '你是一位资深重构专家。请针对以下被识别为代码异味（如上帝类、过长方法）的代码，分析职责边界并给出可执行的重构方案。',
    NULL,
    '## 代码信息
- 目标: {target}
- 异味类型: {smellType}
- 代码内容:
```
{code}
```

## 分析要求
1. 分析当前职责，指出违反单一职责之处。
2. 给出拆分建议：应拆成几个类/方法，各自职责。
3. 给出重构后的代码框架（骨架即可）。
4. 评估对现有调用关系的影响与风险。',
    '{
  "summary": "重构总体思路一句话",
  "responsibilities": ["当前承担的职责1", "职责2"],
  "splitSuggestions": [
    { "newUnit": "OrderValidator", "responsibility": "校验订单", "movedMethods": ["validate"] }
  ],
  "refactoredSkeleton": "重构后的代码骨架（伪代码或类声明）",
  "impacts": ["调用方需调整的点"],
  "risk": "MEDIUM"
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- report-insight
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'report-insight',
    '1.0',
    'report',
    '你是一位迁移交付负责人。请根据以下图谱质量与迁移就绪度指标，生成按优先级排序的、可执行的行动清单。',
    NULL,
    '## 报告指标
{metrics}

## 低置信 / 孤立 / 未覆盖 摘要
{gaps}

## 生成要求
请输出按优先级排序的行动项（actions），每项必须：
- 关联具体的图谱节点、证据或报告指标来源（source）。
- 指明动作类型 actionType：补证据(ADD_EVIDENCE)、生成测试(GENERATE_TEST)、迁移风险处置(MIGRATION_RISK)、批量确认(BATCH_CONFIRM)、人工细看(MANUAL_REVIEW)。
- 给出优先级 priority：HIGH、MEDIUM、LOW。
- 给出简明理由 rationale 与预期收益 expectedBenefit。

## 重要约束
- 不得编造不在指标中的节点或数据。
- 建议要具体、可执行，而非泛泛而谈。',
    '{
  "summary": "总体结论一句话",
  "actions": [
    {
      "title": "为低置信 API 节点补充证据",
      "actionType": "ADD_EVIDENCE",
      "priority": "HIGH",
      "source": "GraphMetricsReport.pendingRatio=0.32",
      "targets": ["api:/order/create"],
      "rationale": "该批节点缺少运行时验证",
      "expectedBenefit": "提升迁移就绪度"
    }
  ]
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- review-suggestion
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'review-suggestion',
    '1.0',
    'review',
    '你是一位资深质量审核专家。请基于以下待审核目标及其支持/冲突证据，输出结构化的审核建议，帮助人工快速决策。',
    NULL,
    '## 审核目标
- 目标类型: {targetType}
- 目标描述: {targetDescription}
- 当前置信度: {currentConfidence}

## 支持证据
- {supportingEvidence}

## 冲突证据
- {conflictingEvidence}

## 任务要求
1. 用一句话总结该目标的可信程度（summary）。
2. 列出支持该目标成立的要点（supportingPoints）。
3. 列出反对或存疑的要点（conflictingPoints）。
4. 给出推荐动作 recommendation，取值必须是：APPROVE（通过）、REJECT（拒绝）、NEED_MORE_INFO（需补充信息）。
5. 给出推荐理由（reasoning）。

## 重要约束
- 证据明显不足或互相矛盾时，倾向 NEED_MORE_INFO，不要硬下结论。
- 不要编造证据中不存在的事实。',
    '{
  "summary": "一句话总结",
  "supportingPoints": ["支持要点1", "支持要点2"],
  "conflictingPoints": ["冲突要点1"],
  "recommendation": "NEED_MORE_INFO",
  "reasoning": "推荐理由"
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- sql-advisor
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'sql-advisor',
    '1.0',
    'sql',
    '你是一位资深数据库性能优化专家。请分析以下 SQL 语句，识别性能问题并给出可执行的优化建议。',
    NULL,
    '## SQL 来源
- 来源标识(Mapper/方法): {sqlKey}
- 相关表结构/索引信息: {schemaInfo}

## 待分析 SQL
```sql
{sql}
```

## 分析要求
请识别以下常见问题（存在才报，不存在不要硬编）：
1. SELECT *（应显式列出字段）
2. 缺少索引 / 索引未命中（结合表结构判断）
3. N+1 查询风险
4. LIKE 前缀模糊匹配（''%xxx'' 导致索引失效）
5. 隐式类型转换、函数包裹列导致索引失效
6. 不合理的 JOIN / 笛卡尔积 / 大结果集未分页

## 字段约束
- severity 取值：HIGH、MEDIUM、LOW。
- 每个问题给出 issueType、说明 description、优化建议 suggestion。
- 如能给出优化后的 SQL，填入 optimizedSql；否则置空字符串。',
    '{
  "sqlKey": "{sqlKey}",
  "issues": [
    { "issueType": "SELECT_STAR", "severity": "MEDIUM", "description": "使用了 SELECT *", "suggestion": "显式列出需要的字段" }
  ],
  "optimizedSql": "SELECT id, name FROM t_user WHERE ...",
  "overallRisk": "MEDIUM",
  "summary": "一句话总结"
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- test-failure-analysis
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'test-failure-analysis',
    '1.0',
    'test',
    '你是一位资深测试与排障工程师。请根据以下测试失败上下文，归纳可能根因并给出排查建议。',
    NULL,
    '## 测试上下文
- 测试用例: {caseName}
- 目标节点: {targetNode}
- 请求: {request}
- 响应: {response}
- 错误信息: {errorMessage}
- 上下游图谱路径: {graphPath}
- 最近运行时 trace: {recentTrace}

## 分析要求
1. 归纳最可能的根因（rootCauses，按可能性排序）。
2. 列出关联的代码/接口/表（relatedArtifacts）。
3. 给出具体排查步骤（troubleshootingSteps）。
4. 判断是否应降低相关节点/边的置信度（shouldLowerConfidence: true/false）。
5. 建议重跑范围（rerunScope）。

## 重要约束
- 只依据给定上下文推断，不要编造不存在的接口或表。
- 证据不足时在 summary 中说明，并将 shouldLowerConfidence 设为 false。',
    '{
  "summary": "一句话根因总结",
  "rootCauses": [ { "cause": "可能根因", "likelihood": "HIGH", "evidence": "依据" } ],
  "relatedArtifacts": ["UserController#register", "t_user"],
  "troubleshootingSteps": ["步骤1", "步骤2"],
  "shouldLowerConfidence": false,
  "rerunScope": ["重跑的用例或范围"]
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;
