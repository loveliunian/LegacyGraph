-- ============================================
-- V58: 同步优化后的 Prompt 模板到数据库
--
-- 将 lg_prompt_template 表中 32 个模板的 system_prompt 和 task_prompt
-- 与 classpath prompts/*.txt 优化版本保持一致，版本统一升级为 2.0。
-- 仅更新 system_prompt、task_prompt、version 字段，
-- 不影响 output_schema、scene、domain_prompt 等其他字段。
-- 使用 PostgreSQL dollar-quoting ($prompt$...$prompt$) 处理多行文本。
-- ============================================

-- add-column-parse
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 用户问题
{question}

## changeKind 判断规则
- ADD_COLUMN: 给表加字段/列（关键词：加字段、加列、新增字段）
- MODIFY_COLUMN: 修改字段类型/长度/约束（关键词：改字段、修改类型、改长度）
- ADD_API: 新增接口（关键词：加接口、新增 API）
- MODIFY_API: 修改接口（关键词：改接口、修改参数）
- REFACTOR: 重构（关键词：重构、拆分、优化结构）
- UNKNOWN: 无法识别具体变更目标

## 抽取规则
- tableName：从问题中提取目标表名（如 lg_change_task），无法确定时置 null。
- columnName：从问题中提取字段名，无法确定时置 null。
- columnType：从问题中提取字段类型（如 VARCHAR(32)），无法确定时置 null。

## 判断指导
- "给 XX 表加一个 YY 字段" → ADD_COLUMN，tableName=XX，columnName=YY
- "把 XX 表的 YY 字段改成 VARCHAR(64)" → MODIFY_COLUMN，tableName=XX，columnName=YY，columnType=VARCHAR(64)
- "重构 XX 模块" → REFACTOR，tableName=null
- 模糊问题（如"怎么改数据库"）→ UNKNOWN

## 输出要求
仅输出严格 JSON，不要其他内容：

{"changeKind":"ADD_COLUMN","tableName":"lg_change_task","columnName":"priority","columnType":"VARCHAR(32)"}$prompt$,
    system_prompt = $prompt$你是变更请求解析器。从用户问题中抽取结构化变更信息，供变更影响分析使用。$prompt$,
    version = '2.0'
WHERE template_code = 'add-column-parse' AND is_active = true;

-- add-column-patch
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 变更信息
- 表: {tableName}
- 新字段: {columnName} {columnType}  nullable={nullable} default={defaultValue}
- 受影响文件: {impactedFiles}

## 输出要求（严格 JSON，对齐 PatchPlan 契约）
1. patches: 至少包含
   - Flyway 迁移脚本 V_next__add_{columnName}_to_{tableName}.sql（ALTER TABLE ... ADD COLUMN）
   - 实体类改动（{TableName}.java 加字段）
   - Mapper XML 改动（INSERT/UPDATE/SELECT 增列）
   - Service / Controller DTO 改动（按影响子图，无证据的文件不得编造）
2. impactedFiles: 仅列影响子图内的文件，每项附 reason
3. validationGates: ["STATIC","UNIT","DB","MIGRATION"]
4. newTests: DB 断言（字段存在 + 默认值）+ 实体字段单测
5. riskLevel 评估标准：
   - HIGH：NOT NULL 无默认值 / 涉及唯一索引 / 涉及外键
   - MEDIUM：NOT NULL 有默认值 / 涉及业务逻辑改动
   - LOW：可空字段 / 纯扩展
6. manualReviewNeeded: 含 NOT NULL 无默认值 / 涉及唯一索引时 true

## 约束
- patchText 必须是可执行的 unified diff 格式。
- evidenceIds 引用影响子图中的证据 ID，无证据的文件不要出现在 patches 中。
- 如果 {impactedFiles} 为空，说明影响子图未建立，riskLevel=HIGH 并在 manualReviewNeeded=true。

## 输出格式
```json
{ "taskType":"ADD_COLUMN", "riskLevel":"LOW", "impactedFiles":[{"path":"...","reason":"..."}],
  "patches":[{"filePath":"...","changeType":"CREATE","patchText":"...","evidenceIds":["..."]}],
  "newTests":[{"type":"DB","target":"...","purpose":"..."}],
  "validationGates":["STATIC","UNIT","DB","MIGRATION"], "manualReviewNeeded":false, "generatedBy":"add-column" }
```
只输出 JSON。$prompt$,
    system_prompt = $prompt$你是资深后端架构师。基于表结构变更与影响子图，生成 ADD_COLUMN 执行计划。$prompt$,
    version = '2.0'
WHERE template_code = 'add-column-patch' AND is_active = true;

-- change-impact
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 变更信息
- 变更目标: {changeTarget}
- 变更描述/diff: {changeDescription}
- 图谱依赖节点: {dependencies}

## 分析要求
1. 判断修改类型 changeType：BUGFIX（缺陷修复）、FEATURE（新功能）、BREAKING_CHANGE（破坏性变更）、REFACTOR（重构）。
2. 评估业务影响严重程度 severity：HIGH（核心功能受影响/破坏性变更）、MEDIUM（局部功能受影响）、LOW（仅内部实现调整，无外部影响）。
3. 预测需要重跑的测试范围 affectedTests。
4. 建议重点回归范围 regressionScope。
5. 列出受影响的关键节点 impactedNodes（来自依赖节点，勿编造）。

## 分层输出指导
- **直接影响**：变更目标自身的改动。
- **间接影响**：沿调用链/依赖链向上追溯的受影响方。
- **数据影响**：如果涉及表结构/字段变更，列出受影响的 Mapper/Service/DTO。

## 证据约束
- impactedNodes 只能来自输入的 {dependencies}，不得编造。
- 如果依赖节点为空或不足以判断，severity 降为 LOW 并在 summary 中说明依据不足。
- affectedTests 和 regressionScope 基于 impactedNodes 推断，给出推断依据。

## 输出格式
请以严格 JSON 格式输出：
```json
{
  "changeType": "BREAKING_CHANGE",
  "severity": "HIGH",
  "summary": "影响总结一句话",
  "impactedNodes": ["api:/order/create", "service:OrderService"],
  "affectedTests": ["下单接口用例"],
  "regressionScope": ["订单创建链路"]
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位资深架构师。请基于变更内容与图谱依赖，做语义级影响分析。$prompt$,
    version = '2.0'
WHERE template_code = 'change-impact' AND is_active = true;

-- code-fact-extraction
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 代码信息
- 项目ID: {projectId}
- 源文件路径: {sourcePath}
- 代码内容:
```
{codeContent}
```

## 抽取要求
请抽取该代码片段中的业务事实条目（每个方法、SQL 操作或关键业务规则可作为一条 item）：
1. 业务功能：方法/片段实际实现什么业务？
2. 输入输出：业务含义是什么？
3. 数据库操作：是否读写数据库，操作哪些表？操作类型必须为 READ / WRITE / UPDATE / DELETE 之一。
4. 业务规则：有哪些重要的判断条件？
5. 依赖调用：调用了哪些其他服务？

## key 格式规范（严格遵守，防止节点重复）
- 方法事实：`method:类名#方法名`
- SQL 事实：`sql:表名#操作类型`（如 `sql:t_order#WRITE`）
- 业务规则：`rule:类名#规则简述`
- 依赖事实：`dep:类名#依赖目标`

## 去重约束
- 同一文件中多个方法操作同一张表时，每个方法单独一条 item，不要合并。
- 如果同一方法在多个代码片段中出现，只抽取一次（基于 key 去重）。
- 纯 getter/setter/toString 等样板方法不需要抽取。

## 重要约束
- 每条结论必须有代码证据（evidence），不得过度推断。
- 证据中的 sourceUri 使用上面的源文件路径，lineStart/lineEnd 标注证据所在行号。
- confidence 为 0~1 之间的小数，证据充分则高，推断成分多则低。

## 输出格式
请以严格 JSON 格式输出，结构如下：
```json
{
  "factType": "method",
  "projectId": "{projectId}",
  "items": [
    {
      "key": "method:类名#方法名",
      "name": "方法或事实名称",
      "attributes": {
        "businessFunction": "一句话描述业务功能",
        "databaseOperations": [ { "tableName": "表名", "operation": "READ" } ],
        "businessRules": [ "规则描述" ],
        "dependencies": [ "依赖的服务类名" ]
      },
      "evidence": [
        {
          "sourceType": "code",
          "sourceUri": "{sourcePath}",
          "lineStart": 1,
          "lineEnd": 20,
          "excerpt": "关键代码片段"
        }
      ],
      "confidence": 0.9
    }
  ]
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位专业的系统分析师，请根据以下提供的代码片段，抽取结构化的业务事实。$prompt$,
    version = '2.0'
WHERE template_code = 'code-fact-extraction' AND is_active = true;

-- code-review
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 代码信息
- 文件路径: {filePath}
- 类名: {className}
- 方法名: {methodName}

## 代码内容:
```
{codeContent}
```

## 审查要求
请从以下方面审查（存在问题才报，不存在不要硬编）：
1. **代码异味**：是否存在设计问题、重复代码、复杂方法（圈复杂度过高）
2. **潜在缺陷**：是否可能存在 NPE、异常未处理、资源泄漏、并发问题
3. **安全问题**：是否存在 SQL 注入、XSS、权限绕过、硬编码密钥
4. **性能问题**：是否存在明显的性能改进空间（N+1 查询、不必要的循环、大对象未释放）
5. **改进建议**：给出具体的改进建议，附代码示例

## 审查指导
- severity 取值：high（可能导致 bug/安全漏洞）、medium（代码质量问题）、low（风格建议）。
- 每个 issue 尽量给出具体行号或位置描述。
- overallScore 为 1-10 的整体评分（10=优秀，1=严重问题）。

## 输出格式
请以严格 JSON 格式输出：
```json
{
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
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位资深代码审查工程师，请审查以下代码片段，指出潜在问题并给出改进建议。$prompt$,
    version = '2.0'
WHERE template_code = 'code-review' AND is_active = true;

-- concurrency-analysis
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 输入数据
- Method 节点（含 @Transactional 注解信息）
- TransactionScope 节点（事务范围聚合）
- CALLS 边（方法调用链）
- synchronized / Lock 节点

## 输出结构

### 事务边界
- 列出 @Transactional 标注的方法
- 附传播行为和隔离级别
- 列出同事务内的方法（通过 TransactionScope 节点聚合）

### 事务传播链路
- 从入口方法沿 CALLS 边追踪事务传播
- 标注每个方法的事务行为（加入外层事务/新开事务/无事务）

### 潜在风险
- self-invocation 导致 @Transactional 失效（同类内部方法调用）
- @Async 方法异常丢失风险
- synchronized 在集群环境无效
- 长事务导致连接占用

### 并发安全
- 列出 synchronized/lock 节点
- 分析锁粒度和有效性

### 修复建议
1. self-invocation → 拆分到独立 Bean 或用 AopContext.currentProxy()
2. synchronized → 改 Redis 分布式锁
3. 长事务 → 拆分事务边界或降级为编程式事务

## 输出格式
请以严格 JSON 格式输出：
```json
{
  "summary": "事务并发分析总体结论一句话",
  "transactionBoundaries": [
    { "method": "OrderService.createOrder", "propagation": "REQUIRED", "isolation": "DEFAULT", "methodsInScope": ["OrderService.createOrder", "OrderMapper.insert", "InventoryMapper.deduct"] }
  ],
  "transactionPropagationChains": [
    { "entry": "OrderController.create", "chain": ["OrderController.create → OrderService.createOrder (REQUIRED) → OrderMapper.insert (加入) → InventoryService.deduct (REQUIRED_NEW)"], "description": "库存扣减在新事务中执行" }
  ],
  "potentialRisks": [
    { "type": "SELF_INVOCATION", "location": "OrderService.createOrder → OrderService.validate", "description": "同类内部调用导致 @Transactional 失效", "severity": "HIGH" },
    { "type": "CLUSTER_UNSAFE_LOCK", "location": "InventoryService.deduct", "description": "使用 synchronized 在集群环境无效", "severity": "HIGH" }
  ],
  "concurrencySafety": [
    { "method": "InventoryService.deduct", "lockType": "synchronized", "granularity": "方法级", "effective": false, "reason": "集群环境下多节点无法互斥" }
  ],
  "fixSuggestions": [
    { "priority": "HIGH", "description": "self-invocation 问题拆分到独立 Bean", "targets": ["OrderService.validate"] },
    { "priority": "HIGH", "description": "synchronized 改为 Redis 分布式锁", "targets": ["InventoryService.deduct"] }
  ]
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一个事务与并发分析专家。根据以下图谱数据，生成结构化事务并发分析报告。$prompt$,
    version = '2.0'
WHERE template_code = 'concurrency-analysis' AND is_active = true;

-- db-schema-analysis
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 数据库 Schema 信息
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
- 每个关系给出 fromTable、toTable、relationType（ONE_TO_MANY / MANY_TO_MANY / REFERENCE）、description

## 约束
- 不要编造 schemaInfo 中不存在的表或字段。
- businessDescription 要基于表名/字段名/注释合理推断，标注"（推断）"如果不确定。
- importance 判断标准：CORE = 核心业务主表，SUPPORT = 关联/日志/配置表，LOOKUP = 字典/枚举表。

## 输出格式
请以严格 JSON 格式输出：

```json
{
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
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位资深数据库架构师和业务分析师。请分析以下数据库表结构，从业务角度理解每张表的含义。$prompt$,
    version = '2.0'
WHERE template_code = 'db-schema-analysis' AND is_active = true;

-- doc-understanding
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 文档信息
- 来源路径: {sourcePath}
- 文档内容:
```
{docContent}
```

## 抽取要求
请识别以下类型的业务事实：
1. 业务域(businessDomains)：大的业务领域，如"订单管理"。
2. 业务流程(businessProcesses)：完整业务流程，含步骤、角色、对象、规则。
3. 业务对象(businessObjects)：核心业务实体及其属性。
4. 业务规则(businessRules)：明确的业务规则或约束。
5. 角色(roles)：参与的角色名称列表。
6. 状态流转(statusTransitions)：业务对象的状态变化。
7. 功能清单(features)：可识别的功能名称列表。

## 跨片段去重指导
本文档可能被分多个片段处理。抽取时遵循以下去重规则：
- 同一业务对象在多个片段出现时，合并属性，只输出一条，取最高 confidence。
- 同一业务流程在多个片段出现时，合并步骤，按文档顺序排列，只输出一条。
- 同一业务规则重复出现时，只保留证据最充分的一条。
- 业务域按名称合并，子表归入同一域。

## 步骤结构化要求
businessProcesses.steps 不要用纯字符串，而用结构化格式：
```json
{ "order": 1, "name": "步骤名称", "role": "操作角色", "action": "具体动作" }
```
若文档未明确角色或动作，对应字段置 null 并标注"（推断）"。

## 重要约束
- 每条结论尽量给出证据(evidence)，evidence 引用文档原文片段。
- confidence 为 0~1 之间的小数；不确定的不要硬编。
- businessObjects.attributes 列出核心属性，不要罗列全部字段。

## 输出格式
请以严格 JSON 格式输出，结构如下（数组可为空，但字段名必须保持一致）：
```json
{
  "businessDomains": [
    { "name": "业务域名称", "description": "简要描述", "confidence": 0.9, "evidenceText": "文档原文" }
  ],
  "businessProcesses": [
    {
      "key": "process:用户注册",
      "name": "流程名称",
      "description": "流程描述",
      "steps": [
        { "order": 1, "name": "步骤1", "role": "角色", "action": "动作描述" }
      ],
      "roles": ["角色"],
      "objects": ["业务对象"],
      "rules": ["规则"],
      "confidence": 0.85,
      "evidence": [ { "sourceType": "doc", "sourceUri": "{sourcePath}", "lineStart": 1, "lineEnd": 5, "excerpt": "原文" } ]
    }
  ],
  "businessObjects": [
    {
      "name": "对象名称",
      "description": "对象描述",
      "attributes": ["核心属性1", "核心属性2"],
      "confidence": 0.8,
      "evidence": [ { "sourceType": "doc", "sourceUri": "{sourcePath}", "excerpt": "原文" } ]
    }
  ],
  "businessRules": [
    { "name": "规则名", "expression": "规则表达式或描述", "confidence": 0.8, "evidence": [] }
  ],
  "roles": ["管理员", "普通用户"],
  "statusTransitions": [
    { "businessObject": "订单", "fromStatus": "待支付", "toStatus": "已支付", "trigger": "支付成功", "confidence": 0.8, "evidence": [] }
  ],
  "features": ["用户注册", "订单支付"],
  "evidence": [
    { "chunkTitle": "片段标题", "contentExcerpt": "片段摘录", "chunkIndex": 0 }
  ]
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位资深业务架构师，请分析以下需求文档片段，抽取结构化业务事实。$prompt$,
    version = '2.0'
WHERE template_code = 'doc-understanding' AND is_active = true;

-- feature-mapping
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 前端页面组件代码
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

## 映射规则（严格遵守）

1. **置信度门槛**：confidence < 0.6 的映射不要输出，放入 unmatched 数组并说明原因。
2. **去重**：同一 apiKey 只保留最高 confidence 的一条映射；同一 buttonName 同理。
3. **聚焦核心业务功能**：非核心 CRUD 模板页面（如通用列表/详情/导出）可合并为一条映射，不要逐字段展开。
4. **权限匹配**：前端权限标识与后端权限注解需精确对应；无法确认权限时 permissionKey 置 null，不要猜测。
5. **businessAction**：用业务语言描述（如"创建订单"），不要用技术语言（如"调用 POST 接口"）。

## 示例

### 示例1：完整匹配（页面+按钮+API+权限齐全）
输入：
- 页面: order/list (权限: order:list)
- 按钮: "新增订单" (权限: order:add)
- API: POST /api/orders (权限: order:add)
- Controller: OrderController.createOrder

输出：
```json
{
  "mappings": [
    {
      "pageKey": "order/list",
      "buttonName": "新增订单",
      "apiKey": "POST /api/orders",
      "businessAction": "创建订单",
      "permissionKey": "order:add",
      "confidence": 0.95,
      "evidence": [{"type": "vue", "excerpt": "新增订单按钮调用 POST /api/orders"}],
      "conflicts": []
    }
  ],
  "unmatched": []
}
```

### 示例2：页面无按钮（纯展示页面）
输入：
- 页面: user/profile
- API: GET /api/users/:id
- Controller: UserController.getUserById

输出：
```json
{
  "mappings": [
    {
      "pageKey": "user/profile",
      "buttonName": null,
      "apiKey": "GET /api/users/:id",
      "businessAction": "查询用户信息",
      "permissionKey": "user:query",
      "confidence": 0.85,
      "evidence": [{"type": "api", "excerpt": "GET /api/users/:id 对应页面用户信息加载"}],
      "conflicts": []
    }
  ],
  "unmatched": []
}
```

### 示例3：无法匹配（无对应 API）
输入：
- 页面: auth/login
- 按钮: "登录"
- API: POST /api/auth/login
- 无法匹配的功能: 密码重置（无对应 API）

输出：
```json
{
  "mappings": [
    {
      "pageKey": "auth/login",
      "buttonName": "登录",
      "apiKey": "POST /api/auth/login",
      "businessAction": "用户登录认证",
      "permissionKey": null,
      "confidence": 0.9,
      "evidence": [{"type": "vue", "excerpt": "登录按钮提交表单到 /api/auth/login"}],
      "conflicts": []
    }
  ],
  "unmatched": ["密码重置"]
}
```

### 示例4：多按钮调同一 API（只保留最高置信度）
输入：
- 页面: order/detail
- 按钮: "保存修改" → PUT /api/orders/:id (confidence 0.88)
- 按钮: "保存并继续" → PUT /api/orders/:id (confidence 0.82)

输出：
```json
{
  "mappings": [
    {
      "pageKey": "order/detail",
      "buttonName": "保存修改",
      "apiKey": "PUT /api/orders/:id",
      "businessAction": "更新订单",
      "permissionKey": "order:update",
      "confidence": 0.88,
      "evidence": [{"type": "vue", "excerpt": "保存修改按钮调用 PUT /api/orders/:id"}],
      "conflicts": []
    }
  ],
  "unmatched": []
}
```

### 示例5：低置信度不输出
输入：
- 页面: dashboard
- API: 无明确对应关系（页面主要是图表聚合，API 来源不确定）

输出：
```json
{
  "mappings": [],
  "unmatched": ["dashboard 页面 API 映射（置信度 0.3，低于门槛 0.6）"]
}
```

## 任务要求
请将上述输入对齐为映射条目：
- 每条映射关联：页面(pageKey)、按钮(buttonName)、API(apiKey)、业务动作(businessAction)、权限(permissionKey)。
- 给出匹配置信度 confidence（0~1），低于 0.6 的放入 unmatched。
- 给出支持证据 evidence；若存在歧义或冲突，记录在 conflicts。
- 无法匹配的项放入 unmatched，并说明原因。
- 同一 apiKey / buttonName 只保留最高 confidence 的一条。

## 输出格式
请以严格 JSON 格式输出，结构如下：
```json
{
  "mappings": [
    {
      "pageKey": "页面标识",
      "buttonName": "按钮名称",
      "apiKey": "HTTP方法 路径",
      "businessAction": "业务动作",
      "permissionKey": "权限点",
      "confidence": 0.9,
      "evidence": [ { "type": "vue", "excerpt": "证据片段" } ],
      "conflicts": []
    }
  ],
  "unmatched": ["未能匹配的功能或接口"]
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位系统分析师，请将前端页面、按钮、API 调用与后端接口、权限、业务动作对齐，建立从 UI → API → Service → 权限的完整链路。$prompt$,
    version = '2.0'
WHERE template_code = 'feature-mapping' AND is_active = true;

-- gap-finder
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 缺口任务
{gapTask}

## 相关 Claim
{claims}

## 相关证据
{evidence}

## 输出要求
1. explanation 用中文说明为什么这是缺口（缺少什么类型的证据/关联）。
2. priorityScore 为 0~1，越高表示越应该优先补证。
   - 0.8-1.0：核心功能缺少关键证据（如 API 无 Controller 关联）。
   - 0.5-0.8：支撑功能缺少部分证据。
   - 0.0-0.5：边缘功能或已有部分证据。
3. suggestedActions 必须是可执行动作，例如补扫代码、上传文档、生成测试、人工确认。
4. requiredEvidenceTypes 只能从 code、db、doc、runtime、test、human_review 中选择。
5. 不得编造输入中不存在的接口、表、方法或业务对象。

## 输出格式
{
  "explanation": "缺口解释",
  "priorityScore": 0.75,
  "suggestedActions": ["补扫 Controller 与前端页面映射", "人工确认功能入口"],
  "requiredEvidenceTypes": ["code", "doc"],
  "needsHumanReview": true
}$prompt$,
    system_prompt = $prompt$你是 LegacyGraph 的图谱缺口分析 Agent。你只能基于输入的 GapTask、Claim 摘要和证据摘要输出建议。$prompt$,
    version = '2.0'
WHERE template_code = 'gap-finder' AND is_active = true;

-- graph-merge-decision
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 候选节点 A
- 键: {candidateAKey}
- 信息:
{candidateAInfo}

## 候选节点 B
- 键: {candidateBKey}
- 信息:
{candidateBInfo}

## 相似度评分（0~1）
- 名称相似度 nameScore: {nameScore}
- 语义相似度 semanticScore: {semanticScore}
- 结构相似度 structScore: {structScore}
- 邻域相似度 neighborScore: {neighborScore}
- 证据相似度 evidenceScore: {evidenceScore}

## 判断规则
- 描述同一个概念（同一个类、业务功能、表）应合并。
- 不同层级概念（业务域 vs 具体功能）不应合并。
- 类型不同但指代同一实体可合并。
- 不确定时偏向 REVIEW（进入人工审核），证据明显冲突则 REJECT。

## 决策取值与门槛
- **AUTO_MERGE**：score ≥ 0.85，且名称+语义相似度均高，无证据冲突。
- **REVIEW**：0.5 ≤ score < 0.85，或存在部分匹配但有歧义。
- **REJECT**：score < 0.5，或证据明显冲突，或类型层级不同。
- score 为最终合并置信度（0~1），综合五个相似度评分得出。

## 判断依据
- reasons 中必须列出至少 2 条理由，引用具体的相似度评分或节点信息。
- positiveEvidenceIds / negativeEvidenceIds 列出支持/反对合并的证据 ID（无则空数组）。

## 输出格式
请以严格 JSON 格式输出，结构如下：
```json
{
  "candidateA": "{candidateAKey}",
  "candidateB": "{candidateBKey}",
  "decision": "REVIEW",
  "score": 0.72,
  "reasons": ["判断理由1", "判断理由2"],
  "positiveEvidenceIds": [],
  "negativeEvidenceIds": []
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$图谱构建过程中发现两个可能重复的节点，请你判断是否应该合并。$prompt$,
    version = '2.0'
WHERE template_code = 'graph-merge-decision' AND is_active = true;

-- graph-rag-planner
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 用户问题
{question}

## 当前查询意图
{intent}

## 相关 Claim（知识断言）
{claims}

## 规划要求

1. **按意图调整查询策略**：
   - `FACT_LOOKUP`：1 跳路径即可，优先 Claim 查询，pathDepth=1。
   - `STRUCTURAL`：2 跳路径，先查模块依赖再查内部结构，pathDepth=2。
   - `RELATIONAL`：3 跳路径，追踪完整调用/数据流链路，pathDepth=3。
   - `COMPARATIVE`：2 跳路径，并行查询两个对比实体的子图。
   - `TEMPORAL`：1 跳即可，重点查节点版本属性。
   - `EXPLANATION`：2 跳路径，先查结构再查关联规则。

2. **拆分子问题**：将用户问题拆分为 2-5 个可独立查询的子问题（subQuestions），按优先级排列。
   - 每个 subQuestion 包含：targetType（如 Feature / ApiEndpoint / Table / BusinessRule）、具体的 query 描述、priority（HIGH/MEDIUM/LOW）

3. **Claim 过滤**：设计 ClaimQuery 列表，每条指定 subjectType、predicate、sourceType、minConfidence 等过滤条件，从 Claim 库检索相关断言。

4. **路径查询**：设计 PathQuery 列表，每条指定 startNodeType（起始节点类型，如 Feature/ApiEndpoint/Table）、relationshipPattern（边类型序列，如 CALLS|HANDLED_BY|READS）、endNodeType（目标节点类型）、pathDepth（最大跳数 1-5）。

5. **证据类型**：列出回答问题所需的证据类型，只能从以下选择：CLAIM（知识断言）、CODE_AST（代码 AST）、TABLE_SCHEMA（表结构）、API_DOC（接口文档）、TRACE（运行时链路）、TEST_RESULT（测试结果）、MANUAL_REVIEW（人工审核）。

## 输出格式
{
  "subQuestions": [
    {"targetType": "ApiEndpoint", "query": "找到订单创建相关的 API 接口", "priority": "HIGH", "dependsOn": []}
  ],
  "claimQueries": [
    {"subjectType": "ApiEndpoint", "predicate": "HANDLED_BY", "sourceType": null, "minConfidence": 0.5},
    {"subjectType": "Feature", "predicate": "EXPOSED_BY", "sourceType": null, "minConfidence": 0.4}
  ],
  "pathQueries": [
    {"startNodeType": "Feature", "relationshipPattern": "EXPOSED_BY|HANDLED_BY|READS", "endNodeType": "Table", "pathDepth": 3},
    {"startNodeType": "ApiEndpoint", "relationshipPattern": "HANDLED_BY|CALLS", "endNodeType": "Method", "pathDepth": 2}
  ],
  "requiredEvidenceTypes": ["CLAIM", "TABLE_SCHEMA"],
  "reasoning": "先找到 Feature→API 入口，再沿调用链找到直接读写的数据表，最后检查表结构确认完整性",
  "needsHumanReview": false
}

## 规则
1. 不得编造 Claim 或系统中不存在的接口、表、方法、业务对象。
2. 如果输入中的 Claim 不足以回答用户问题，设为 needsHumanReview=true 并说明原因。
3. 优先级排序：先查 Feature 入口（EXPOSED_BY）→ 再查 API 实现（HANDLED_BY/CALLS）→ 再查数据路径（READS/WRITES）→ 最后查规则/权限。
4. pathDepth 不超过当前意图推荐深度（见上方策略表）。
5. 使用中文输出 reasoning 和所有描述性字段。$prompt$,
    system_prompt = $prompt$你是 LegacyGraph 的 GraphRAG 查询规划 Agent。你需要根据用户的自然语言问题，结合已有的知识断言（KnowledgeClaim），规划多步查询路径以回答该问题。$prompt$,
    version = '2.0'
WHERE template_code = 'graph-rag-planner' AND is_active = true;

-- hyde-generator
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 问题
{question}

## 要求
- 生成 100-200 字的假设性答案。
- 使用专业的技术术语，包含可能的关键词和实体名称。
- 模拟真实答案的结构：如果问"有哪些"，用列表格式；如果问"是什么"，用描述格式；如果问"怎么做"，用步骤格式。
- 包含可能的表名、接口名、类名等实体（基于问题领域合理推测）。

输出 JSON：{"hypotheticalAnswer": "假设性答案内容"}

注意：只输出 JSON，不要输出其他内容。$prompt$,
    system_prompt = $prompt$请根据问题生成一个假设性的答案段落。这个答案不需要准确，只需要在语义和格式上与真实答案相关，用于提高检索效果。$prompt$,
    version = '2.0'
WHERE template_code = 'hyde-generator' AND is_active = true;

-- implementation-plan
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 用户需求
{requirement}

## 检索到的上下文（图谱节点 / 文档片段 / 已有实现）
{context}

## 输出要求
方案须包含以下章节，按顺序输出，缺失项填"（无）"并说明原因：

### 1. 需求分解
- 把用户需求拆成若干可独立验收的子需求（subRequirements）。
- 每个子需求标注所属层（数据库/实体/Mapper/Service/Controller/前端/测试）与验收标准。

### 2. 实现方案（按分层）
按以下分层逐一给出实现要点；每层若无需改动填"（无）"：
- 数据库层：表/字段/索引/DDL
- 实体层：Entity / DTO / VO
- Mapper 层：Mapper 接口 / SQL（含分页、批量）
- Service 层：业务逻辑、事务边界、对外暴露方法
- Controller 层：接口路径、入参出参、鉴权
- 前端层：页面/组件、接口对接、状态管理
每层给出"新建/修改"标注与文件路径建议（路径基于上下文中真实存在的目录，勿编造）。

### 3. 可复用组件
- 列出上下文中已存在、可直接复用的 Controller/Service/工具类/前端组件。
- 每项标注复用方式（直接调用/继承/组合）与所在文件。

### 4. 风险与注意事项
- 性能（N+1、大结果集）、事务一致性、并发、安全（越权/SQL 注入）、向后兼容。
- 每项给出 severity（HIGH/MEDIUM/LOW）与规避建议。

### 5. 实施步骤
按顺序给出可执行步骤，每步列出具体文件路径与方法名：
DDL → 实体 → Mapper → Service → Controller → 前端 → 测试

## 重要约束
- 只依据上下文推断已有目录结构与可复用实现，不得编造上下文中不存在的表名、接口、模块。
- 上下文不足以确定的部分，明确标注"（推断）"或"待确认"。
- 不输出 diff，只输出方案。
- 如果需求与现有系统完全无关（上下文无匹配），summary 说明"上下文不足，无法生成方案"。

## 输出格式
请以严格 JSON 格式输出：
```json
{
  "summary": "方案一句话总览",
  "subRequirements": [
    { "name": "子需求名", "layer": "数据库", "acceptance": "验收标准" }
  ],
  "layers": {
    "database": { "changes": [ { "type": "NEW|MODIFY", "path": "db/migration/Vxx__xxx.sql", "detail": "建表/加字段/索引" } ], "note": "（无）" },
    "entity":   { "changes": [ { "type": "NEW", "path": ".../OrderExport.java", "detail": "导出 DTO" } ], "note": "" },
    "mapper":   { "changes": [ { "type": "NEW", "path": ".../OrderMapper.java", "detail": "导出查询 SQL" } ], "note": "" },
    "service":  { "changes": [ { "type": "NEW", "path": ".../OrderExportService.java", "detail": "导出业务编排" } ], "note": "" },
    "controller": { "changes": [ { "type": "NEW", "path": ".../OrderExportController.java", "detail": "导出接口" } ], "note": "" },
    "frontend": { "changes": [ { "type": "NEW", "path": "src/views/order/Export.vue", "detail": "导出按钮与下载" } ], "note": "" }
  },
  "reusableComponents": [
    { "name": "FileDownloader", "path": ".../util/FileDownloader.java", "reuseWay": "直接调用", "reason": "已支持流式下载" }
  ],
  "risks": [
    { "category": "性能", "severity": "MEDIUM", "description": "全量导出可能 OOM", "mitigation": "分页/流式写入" }
  ],
  "steps": [
    { "order": 1, "layer": "DDL", "target": "新建 order_export_task 表", "files": ["db/migration/Vxx__order_export_task.sql"] },
    { "order": 2, "layer": "实体", "target": "新建 OrderExportTask 实体", "files": [".../entity/OrderExportTask.java"] },
    { "order": 3, "layer": "Mapper", "target": "新增导出查询", "files": [".../mapper/OrderMapper.java"] },
    { "order": 4, "layer": "Service", "target": "导出业务编排", "files": [".../service/OrderExportService.java"] },
    { "order": 5, "layer": "Controller", "target": "导出接口", "files": [".../controller/OrderExportController.java"] },
    { "order": 6, "layer": "前端", "target": "导出按钮与下载", "files": ["src/views/order/Export.vue"] },
    { "order": 7, "layer": "测试", "target": "单元与集成测试", "files": [".../OrderExportServiceTest.java"] }
  ]
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位资深全栈架构师。请基于用户需求与检索到的代码图谱/文档上下文，给出一份可直接落地的分层实施方案。$prompt$,
    version = '2.0'
WHERE template_code = 'implementation-plan' AND is_active = true;

-- intent-classifier
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 分类规则
- FACT_LOOKUP: 查找具体事实（方法列表、表结构、接口参数、字段含义）
- STRUCTURAL: 查找系统结构（模块关系、依赖链路、分层架构）
- RELATIONAL: 查找实体间关系（调用关系、数据流、权限链路）
- COMPARATIVE: 对比两个或多个实体（版本差异、方案对比、前后变化）
- TEMPORAL: 涉及时间、版本变更、演进历史
- EXPLANATION: 需要解释原因、设计决策、架构选型理由
- CHANGE_IMPACT: 涉及变更如何执行（加字段/改表/加列/删字段/加接口/改接口/重构怎么做/需要改哪些地方）。这类问题不是查询现状，而是询问变更如何落地。关键词：加、增、改、删、新增、修改、怎么改、需要做哪些改动

## 判断指导
- 优先匹配最具体的意图：如"加字段需要改哪些地方"是 CHANGE_IMPACT 而非 RELATIONAL。
- "XX和YY有什么关系" → RELATIONAL；"XX和YY有什么区别" → COMPARATIVE。
- "为什么这样设计" → EXPLANATION；"这个接口什么时候加的" → TEMPORAL。
- 模糊时优先选 FACT_LOOKUP（最通用的兜底意图）。

## 用户问题
{question}

## 对话历史
{history}

输出 JSON：{"intent": "FACT_LOOKUP", "confidence": 0.9}

注意：只输出 JSON，不要输出其他内容。$prompt$,
    system_prompt = $prompt$你是一个查询意图分类器。根据用户问题和对话历史，判断查询类型。$prompt$,
    version = '2.0'
WHERE template_code = 'intent-classifier' AND is_active = true;

-- migration-convert
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 迁移信息
- 迁移方向: {migrationDirection}
- 源文件: {sourcePath}
- 代码内容:
```
{code}
```
- 自定义迁移规则（可选）: {customRules}

## 分析要求
识别并转换常见迁移点（存在才报，不存在不要硬编）：
1. 过时注解（如 `@Autowired` 字段注入 → 构造注入）。
2. 包名迁移（`javax.*` → `jakarta.*`）。
3. 配置/API 变化（如 Spring Boot 2 → 3 的配置项变更）。
4. 自定义规则匹配项。
5. 废弃 API 替换（如 `WebSecurityConfigurerAdapter` → `SecurityFilterChain`）。

## 约束
- 只报告实际存在的变化点，不要编造代码中不存在的问题。
- migratedCode 必须是完整的转换后代码，不是 diff。
- manualReviewNeeded 列出无法自动确定的点（如语义级改动需要人工确认）。
- 如果代码无需任何迁移改动，summary 写"无迁移点"，changes 为空数组。

## 输出格式
请以严格 JSON 格式输出：
```json
{
  "summary": "迁移要点一句话",
  "changes": [
    { "ruleType": "JAVAX_TO_JAKARTA", "before": "javax.persistence.Entity", "after": "jakarta.persistence.Entity", "reason": "Spring Boot 3 使用 jakarta" }
  ],
  "migratedCode": "转换后的代码",
  "manualReviewNeeded": ["需人工确认的点"]
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位 Spring Boot 迁移专家。请针对以下代码，按目标迁移规则给出转换建议与转换后代码。$prompt$,
    version = '2.0'
WHERE template_code = 'migration-convert' AND is_active = true;

-- patch-plan
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 任务信息
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
1. 只能改动"允许改动的文件"列表内的文件，越界文件不得出现在 patches 中。
2. 每个 patch 必须是 unified diff（含 `---`/`+++`/`@@` 标记），能干净应用。
3. 每个 patch 至少引用一个 evidenceId。
4. 无法确认修复点时，降低 riskLevel 并在 manualReviewNeeded=true。
5. 只输出 JSON，不要其他解释内容。

## 补丁质量要求
- patches 应针对根因修复，不要只处理症状。
- 每个 patch 的 changeType 取值：MODIFY（修改）、CREATE（新建）、DELETE（删除）。
- newTests 必须覆盖修复点，防止回归。
- validationGates 至少包含 STATIC 和 UNIT。

## 输出格式
请以严格 JSON 格式输出：
```json
{
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
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位资深工程师。请基于给定的影响子图、证据与失败测试，为 bug 修复生成**可校验的补丁计划**。你的目标不是猜测，而是基于输入事实产出可应用、可验证的补丁。$prompt$,
    version = '2.0'
WHERE template_code = 'patch-plan' AND is_active = true;

-- pr-description
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 变更信息
- 分支: {branch}
- 关联 issue: {issue}
- 变更 diff / 摘要:
```
{diff}
```

## 生成要求
1. commitMessage 遵循 Conventional Commits（feat/fix/refactor/docs/chore/breaking 等）。
   - 格式：`type(scope): 简述`，如 `feat(order): 支持订单批量取消`。
   - breaking change 在 type 后加 `!`，如 `feat(order)!: 重构取消逻辑`。
2. prTitle 简明扼要，不超过 50 字。
3. prBody 用 Markdown 格式，包含以下章节：
   - **变更目的**：为什么做这个改动。
   - **影响范围**：涉及哪些模块/接口/表。
   - **测试要点**：如何验证此变更。
4. 若提供了 issue，在 prBody 末尾关联（如 `Closes #123`）。
5. changeType 与 commitMessage 的 type 保持一致。

## 输出格式
请以严格 JSON 格式输出：
```json
{
  "commitMessage": "feat(order): 支持订单批量取消",
  "prTitle": "订单批量取消功能",
  "prBody": "## 变更目的\n...\n## 影响范围\n...\n## 测试\n...\n\nCloses #123",
  "changeType": "feat"
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位资深工程师。请根据 git diff 变更内容，按 Conventional Commits 规范生成提交信息与 PR 描述。$prompt$,
    version = '2.0'
WHERE template_code = 'pr-description' AND is_active = true;

-- procedure-guide
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 用户问题
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
- 资料清单与步骤要具体可执行，避免空泛描述。

## 输出格式
请以严格 JSON 格式输出：
```json
{
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
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位资深业务顾问。请基于检索到的业务流程图谱节点、所需资料、相关接口与文档片段，为用户给出一份清晰可执行的操作指南。$prompt$,
    version = '2.0'
WHERE template_code = 'procedure-guide' AND is_active = true;

-- qa-answer-enhanced
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 回答规则
1. **严格基于上下文**：只使用提供的上下文信息回答，不要编造任何上下文中不存在的内容（表名、接口、模块、方法等）。
2. **信息不足时诚实说明**：如果上下文不足以完整回答问题，明确说明"根据现有图谱信息，无法完整回答该问题"，并指出缺少哪些信息。
3. **引用来源**：每个关键结论后用方括号标注来源：
   - 图谱节点：`[节点:api:/order/create]` 或 `[节点:table:t_order]`
   - 文档片段：`[文档:需求文档.md#chunk2]`
   - 代码位置：`[代码:OrderService.java#L45-60]`
4. **证据强度标注**：确定结论直接陈述；推断结论标注"（推断）"；不确定的标注"（待确认）"。
5. **语言专业简洁**：使用中文，技术术语保留英文原词。

## 按意图调整回答策略
- **事实查询（FACT_LOOKUP）**：用列表/表格直接罗列结果，不做展开。
- **结构查询（STRUCTURAL）**：用分层结构展示模块/依赖关系，适当使用缩进或列表。
- **关系查询（RELATIONAL）**：用"→"链路展示调用/数据流，如 `Controller → Service → Mapper → Table`。
- **对比查询（COMPARATIVE）**：用表格对比差异，高亮变更项。
- **变更影响（CHANGE_IMPACT）**：分层展开——影响范围 → 受影响节点 → 建议回归测试 → 风险等级。
- **解释查询（EXPLANATION）**：先给结论，再展开原因和依据。

## Markdown 格式指导
- 列举多项同类信息时用列表（`-` 或编号列表）。
- 对比类信息用表格。
- 代码/路径/字段名用行内代码标记。
- 调用链路用 `A → B → C` 格式。
- 不要用标题（#），回答直接从正文开始。

## 对话历史
{history}

## 检索上下文
{context}

## 用户问题
{question}

## 回答$prompt$,
    system_prompt = $prompt$你是 LegacyGraph 代码知识图谱问答助手。请基于检索到的上下文信息回答用户问题。$prompt$,
    version = '2.0'
WHERE template_code = 'qa-answer-enhanced' AND is_active = true;

-- qa-answer
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 用户问题
{question}

## 检索到的上下文（图谱节点 / 文档片段）
{context}

## 回答要求
1. 只能依据上下文作答；上下文不足以回答时，answer 说明"现有图谱信息不足以回答"，并降低 confidence。
2. 不得编造上下文中不存在的表名、接口、模块。
3. usedEvidence 必须引用真实出现在上下文中的来源标识（如节点 key 或片段编号）。
4. confidence 为 0~1 之间的小数，反映回答的可靠程度。
5. relatedNodeKeys 列出与答案最相关的节点 key。
6. answer 中每个关键结论后用方括号标注来源标识，如 `[api:/user/register]` 或 `[chunk#2]`。

## 输出格式
请以严格 JSON 格式输出，结构如下：
```json
{
  "answer": "对问题的自然语言回答",
  "confidence": 0.82,
  "usedEvidence": ["node:api:/user/register", "chunk#2"],
  "relatedNodeKeys": ["api:/user/register", "table:t_user"]
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是 LegacyGraph 平台的智能助手。请基于检索到的图谱节点与文档片段（上下文），用中文回答用户问题。$prompt$,
    version = '2.0'
WHERE template_code = 'qa-answer' AND is_active = true;

-- query-rewriter
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 改写策略
1. 同义词替换：用不同词汇表达相同概念（如"接口"→"API"、"方法"→"函数"）。
2. 视角转换：从不同角度描述同一问题（如"X调用了什么"→"X的下游依赖有哪些"）。
3. 具体化/抽象化：将抽象问题具体化，或将具体问题抽象化。
4. 实体补全：如果问题中包含简写或别名，改写时补全全名。

## 查询意图
{intent}

## 原始问题
{question}

## 改写约束
- 改写后的变体必须与原始问题语义等价，不要引入原始问题未涉及的新实体。
- 改写要自然，不要机械替换。
- 如果原始问题已经很明确，可以只输出 1-2 个变体。

输出 JSON：{"rewrites": ["改写1", "改写2", "改写3"]}

注意：只输出 JSON，不要输出其他内容。$prompt$,
    system_prompt = $prompt$你是一个查询改写专家。将用户问题改写为 2-3 个语义等价的变体，以提高检索召回率。$prompt$,
    version = '2.0'
WHERE template_code = 'query-rewriter' AND is_active = true;

-- refactor-suggestion
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 代码信息
- 目标: {target}
- 异味类型: {smellType}
- 代码内容:
```
{code}
```

## 分析要求
1. 分析当前职责，指出违反单一职责之处。
2. 给出拆分建议：应拆成几个类/方法，各自职责。
3. 给出重构后的代码框架（骨架即可，不需要完整实现）。
4. 评估对现有调用关系的影响与风险。
5. 给出重构优先级建议（是否值得立即重构）。

## 约束
- splitSuggestions 中的 newUnit 名称要语义化，体现职责。
- movedMethods 列出从原类迁移到新单元的方法名。
- refactoredSkeleton 用伪代码或类声明展示骨架。
- risk 取值：HIGH（影响面大/调用方多）、MEDIUM、LOW。

## 输出格式
请以严格 JSON 格式输出：
```json
{
  "summary": "重构总体思路一句话",
  "responsibilities": ["当前承担的职责1", "职责2"],
  "splitSuggestions": [
    { "newUnit": "OrderValidator", "responsibility": "校验订单", "movedMethods": ["validate"] }
  ],
  "refactoredSkeleton": "重构后的代码骨架（伪代码或类声明）",
  "impacts": ["调用方需调整的点"],
  "risk": "MEDIUM"
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位资深重构专家。请针对以下被识别为代码异味（如上帝类、过长方法）的代码，分析职责边界并给出可执行的重构方案。$prompt$,
    version = '2.0'
WHERE template_code = 'refactor-suggestion' AND is_active = true;

-- report-insight
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 报告指标
{metrics}

## 低置信 / 孤立 / 未覆盖 摘要
{gaps}

## 生成要求
请输出按优先级排序的行动项（actions），每项必须：
- 关联具体的图谱节点、证据或报告指标来源（source）。
- 指明动作类型 actionType：补证据(ADD_EVIDENCE)、生成测试(GENERATE_TEST)、迁移风险处置(MIGRATION_RISK)、批量确认(BATCH_CONFIRM)、人工细看(MANUAL_REVIEW)。
- 给出优先级 priority：HIGH（阻断迁移/核心功能）、MEDIUM（质量提升）、LOW（锦上添花）。
- 给出简明理由 rationale 与预期收益 expectedBenefit。

## 重要约束
- 不得编造不在指标中的节点或数据。
- 建议要具体、可执行，而非泛泛而谈。
- 如果指标已达标（如 pendingRatio < 0.1），不要硬凑行动项，summary 说明"指标健康"。
- HIGH 优先级行动项不超过 3 个，避免行动项过多无法聚焦。

## 输出格式
请以严格 JSON 格式输出：
```json
{
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
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位迁移交付负责人。请根据以下图谱质量与迁移就绪度指标，生成按优先级排序的、可执行的行动清单。$prompt$,
    version = '2.0'
WHERE template_code = 'report-insight' AND is_active = true;

-- review-suggestion
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 审核目标
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

## 判断指导
- 当前置信度 {currentConfidence} ≥ 0.85 且无冲突证据 → 倾向 APPROVE。
- 有冲突证据但可解释 → NEED_MORE_INFO，列出需补充的信息。
- 冲突证据与支持证据直接矛盾 → REJECT。
- 不要因证据不足就 APPROVE；不确定时选 NEED_MORE_INFO。

## 重要约束
- 证据明显不足或互相矛盾时，倾向 NEED_MORE_INFO，不要硬下结论。
- 不要编造证据中不存在的事实。
- supportingPoints 和 conflictingPoints 要引用具体证据内容。

## 输出格式
请以严格 JSON 格式输出，结构如下：
```json
{
  "summary": "一句话总结",
  "supportingPoints": ["支持要点1", "支持要点2"],
  "conflictingPoints": ["冲突要点1"],
  "recommendation": "NEED_MORE_INFO",
  "reasoning": "推荐理由"
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位资深质量审核专家。请基于以下待审核目标及其支持/冲突证据，输出结构化的审核建议，帮助人工快速决策。$prompt$,
    version = '2.0'
WHERE template_code = 'review-suggestion' AND is_active = true;

-- security-audit-report
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 输入数据
- SecurityRisk 节点（riskType: SQL_INJECTION / HARDCODED_SECRET / UNSAFE_DESERIALIZATION）
- Column 节点（sensitive=true/false，MASKED_AT 边）
- ApiEndpoint 节点（REQUIRES_PERMISSION 边）

## 输出结构

### SQL 注入风险
- 列出 riskType=SQL_INJECTION 的 SecurityRisk 节点
- 标注严重等级和位置（文件:行号）

### 硬编码密钥
- 列出 riskType=HARDCODED_SECRET 的 SecurityRisk 节点
- 标注密钥类型和位置

### 敏感数据处理
- 列出 sensitive=true 的 Column 节点
- 标注脱敏位置（Column --MASKED_AT--> Method）

### 权限校验缺失
- 列出无 REQUIRES_PERMISSION 边的 ApiEndpoint 节点
- 统计覆盖率：有权限校验 / 全量接口

### 反序列化风险
- 列出 riskType=UNSAFE_DESERIALIZATION 的 SecurityRisk 节点

### 修复建议
1. 立即修复：硬编码密钥 → 改为环境变量
2. 高危：SQL 注入 → 改为 #{} 参数化
3. 中危：补齐权限校验

## 输出格式
请以严格 JSON 格式输出：
```json
{
  "summary": "安全审计总体结论一句话",
  "sqlInjectionRisks": [
    { "nodeName": "sec-risk-001", "location": "UserMapper.java:45", "severity": "HIGH", "description": "使用 ${} 拼接 SQL" }
  ],
  "hardcodedSecrets": [
    { "nodeName": "sec-risk-002", "location": "Config.java:12", "secretType": "数据库密码", "severity": "HIGH" }
  ],
  "sensitiveDataHandling": [
    { "columnName": "t_user.phone", "sensitive": true, "maskedAt": "UserService.maskPhone", "description": "手机号已脱敏" }
  ],
  "permissionGaps": [
    { "apiEndpoint": "GET /api/internal/health", "hasPermission": false, "severity": "MEDIUM" }
  ],
  "deserializationRisks": [
    { "nodeName": "sec-risk-003", "location": "RpcService.java:78", "severity": "HIGH", "description": "使用 ObjectInputStream 反序列化外部输入" }
  ],
  "fixSuggestions": [
    { "priority": "HIGH", "description": "硬编码密钥改为环境变量", "targets": ["Config.java:12"] },
    { "priority": "HIGH", "description": "SQL 注入改为参数化查询", "targets": ["UserMapper.java:45"] },
    { "priority": "MEDIUM", "description": "补齐接口权限校验", "targets": ["GET /api/internal/health"] }
  ],
  "permissionCoverage": { "total": 50, "withPermission": 42, "withoutPermission": 8, "coverageRate": 0.84 }
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一个安全审计专家。根据以下图谱数据，生成结构化安全审计报告。$prompt$,
    version = '2.0'
WHERE template_code = 'security-audit-report' AND is_active = true;

-- sql-advisor
UPDATE lg_prompt_template
SET task_prompt = $prompt$## SQL 来源
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
4. LIKE 前缀模糊匹配（'%xxx' 导致索引失效）
5. 隐式类型转换、函数包裹列导致索引失效
6. 不合理的 JOIN / 笛卡尔积 / 大结果集未分页
7. 子查询可优化为 JOIN
8. OR 条件导致索引失效

## 字段约束
- severity 取值：HIGH（可能导致慢查询/锁表）、MEDIUM（性能下降风险）、LOW（优化建议）。
- 每个问题给出 issueType、说明 description、优化建议 suggestion。
- 如能给出优化后的 SQL，填入 optimizedSql；否则置空字符串。
- overallRisk 取最高 severity 的问题等级。

## 输出格式
请以严格 JSON 格式输出：
```json
{
  "sqlKey": "{sqlKey}",
  "issues": [
    { "issueType": "SELECT_STAR", "severity": "MEDIUM", "description": "使用了 SELECT *", "suggestion": "显式列出需要的字段" }
  ],
  "optimizedSql": "SELECT id, name FROM t_user WHERE ...",
  "overallRisk": "MEDIUM",
  "summary": "一句话总结"
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位资深数据库性能优化专家。请分析以下 SQL 语句，识别性能问题并给出可执行的优化建议。$prompt$,
    version = '2.0'
WHERE template_code = 'sql-advisor' AND is_active = true;

-- tech-debt-report
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 输入数据
- 图谱节点（Package / Class / Method / ApiEndpoint 等）及属性
- 图谱边（CALLS / DEPENDS_ON / READS / WRITES 等）
- 节点规模指标（lineCount / methodCount / fanIn / fanOut）

## 输出结构

### 循环依赖
- 列出检测到的环路径（Package/Class/Method 级）
- 标注环类型：DEPENDS_ON（包级循环）或 CALLS（方法/类级循环）
- 给出打破环的建议

### 过大类/方法
- 列出 lineCount > 1000 或 methodCount > 30 的类
- 附规模指标：行数、方法数、字段数
- 给出拆分建议（按职责拆分方向）

### 架构违规
- 列出 Controller → Mapper 跳层调用（绕过 Service）
- 标注违规类型和位置

### 死代码
- 列出 fanIn=0 的 Method/Class 节点
- 标注是否为入口节点（Controller/ApiEndpoint 不算死代码）

### 高耦合模块
- 列出 fanOut > 20 的 Package 节点
- 给出解耦建议

### 优先级建议
1. 立即修复：循环依赖（阻断编译/部署）
2. 近期重构：过大类（影响可维护性）
3. 长期清理：死代码

## 输出格式
请以严格 JSON 格式输出：
```json
{
  "summary": "技术债总体评估一句话",
  "circularDependencies": [
    { "path": "A → B → C → A", "type": "CALLS", "suggestion": "引入接口解耦" }
  ],
  "largeClasses": [
    { "className": "OrderService", "lineCount": 1200, "methodCount": 35, "fieldCount": 12, "suggestion": "拆分为 OrderQueryService + OrderCommandService" }
  ],
  "architectureViolations": [
    { "violationType": "SKIP_LAYER", "location": "OrderController → OrderMapper", "description": "Controller 直接调用 Mapper 绕过 Service" }
  ],
  "deadCode": [
    { "nodeName": "OldUtil", "nodeType": "Class", "isEntry": false }
  ],
  "highCouplingModules": [
    { "packageName": "com.xxx.order", "fanOut": 25, "suggestion": "拆分子包" }
  ],
  "priorityActions": [
    { "priority": "HIGH", "description": "修复循环依赖", "items": ["A → B → C → A"] },
    { "priority": "MEDIUM", "description": "拆分过大类", "items": ["OrderService"] },
    { "priority": "LOW", "description": "清理死代码", "items": ["OldUtil"] }
  ]
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一个技术债分析专家。根据以下图谱数据，生成结构化技术债报告。$prompt$,
    version = '2.0'
WHERE template_code = 'tech-debt-report' AND is_active = true;

-- test-case-generation
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 功能信息
- 功能键: {featureKey}
- 功能名称: {featureName}
- API 端点: {apiEndpoint}
- HTTP 方法: {httpMethod}
- 请求结构(Schema): {requestSchema}
- 相关数据表: {relatedTables}
- 业务规则: {businessRules}

## 生成要求
1. 正常路径(NORMAL)：合法输入 → 期望成功，断言关键响应字段。
2. 异常路径(EXCEPTION)：非法/缺失输入 → 期望错误码与错误信息。
3. 边界条件(BOUNDARY)：边界值、空值、极值、特殊字符。
4. 若涉及数据库，补充 SQL 断言（基于相关数据表）。
5. 请求参数尽量根据 requestSchema 填充真实示例值；无法确定的放入 needHumanInput。

## 异常用例模式指导
- 必填字段缺失：逐个 omission 每个必填字段。
- 类型不匹配：字符串传数字、数字传超长字符串。
- 权限边界：无权限访问、跨租户访问。
- 并发冲突：重复提交、乐观锁冲突。
- 数据不存在：查询不存在的 ID（404）。
- 业务规则违反：违反 {businessRules} 中的约束。

## 字段约束
- caseType 取值：API、E2E、DB、HYBRID。
- assertions[].type 取值：HTTP、JSON_PATH、SQL、STATE、UI。
- assertions[].expression 为可执行断言表达式，例如 `status == 200`、`$.data.id != null`、`SELECT COUNT(*) FROM t_user WHERE ... = 1`。
- preconditions 描述测试前置条件（如"数据库中存在 ID=1 的用户"）。

## 输出格式
请以严格 JSON 格式输出，结构如下：
```json
{
  "testCases": [
    {
      "featureKey": "{featureKey}",
      "caseName": "测试用例名称",
      "caseType": "API",
      "preconditions": ["前置条件"],
      "steps": ["步骤1", "步骤2"],
      "request": {
        "method": "{httpMethod}",
        "url": "{apiEndpoint}",
        "headers": {},
        "body": {}
      },
      "assertions": [
        { "type": "HTTP", "expression": "status == 200" },
        { "type": "JSON_PATH", "expression": "$.data.id != null" }
      ],
      "needHumanInput": []
    }
  ]
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位资深测试工程师。请根据以下功能节点信息，生成可执行的自动化测试用例，覆盖正常、异常、边界场景，并尽量填充真实请求参数与断言。$prompt$,
    version = '2.0'
WHERE template_code = 'test-case-generation' AND is_active = true;

-- test-failure-analysis
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 测试上下文
- 测试用例: {caseName}
- 目标节点: {targetNode}
- 请求: {request}
- 响应: {response}
- 错误信息: {errorMessage}
- 上下游图谱路径: {graphPath}
- 最近运行时 trace: {recentTrace}

## 分析要求
1. 归纳最可能的根因（rootCauses，按可能性排序，likelihood 取 HIGH/MEDIUM/LOW）。
2. 列出关联的代码/接口/表（relatedArtifacts）。
3. 给出具体排查步骤（troubleshootingSteps），按顺序排列。
4. 判断是否应降低相关节点/边的置信度（shouldLowerConfidence: true/false）。
5. 建议重跑范围（rerunScope）。

## 分析指导
- 优先从 errorMessage 和 recentTrace 中提取直接线索。
- 如果 errorMessage 明确指向某个方法/接口，该根因 likelihood=HIGH。
- 如果只有间接线索（如超时但无具体错误），likelihood=MEDIUM 或 LOW。
- rootCauses 至少给出 1 个，最多 3 个，按 likelihood 降序排列。
- shouldLowerConfidence=true 仅当根因明确指向图谱节点/边数据错误时。

## 重要约束
- 只依据给定上下文推断，不要编造不存在的接口或表。
- 证据不足时在 summary 中说明，并将 shouldLowerConfidence 设为 false。
- troubleshootingSteps 要具体可执行（如"检查 XxxService.java 第 45 行的空指针分支"），不要泛泛而谈。

## 输出格式
请以严格 JSON 格式输出：
```json
{
  "summary": "一句话根因总结",
  "rootCauses": [ { "cause": "可能根因", "likelihood": "HIGH", "evidence": "依据" } ],
  "relatedArtifacts": ["UserController#register", "t_user"],
  "troubleshootingSteps": ["步骤1", "步骤2"],
  "shouldLowerConfidence": false,
  "rerunScope": ["重跑的用例或范围"]
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$你是一位资深测试与排障工程师。请根据以下测试失败上下文，归纳可能根因并给出排查建议。$prompt$,
    version = '2.0'
WHERE template_code = 'test-failure-analysis' AND is_active = true;

-- test-generation
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 输入
- 目标方法签名和参数类型
- 依赖列表（需 mock 的对象）
- 现有测试（避免重复）
- 契约信息（如适用）

## 生成要求
1. 使用 JUnit 5 + Mockito 框架。
2. 覆盖以下测试场景：
   - **正常路径**：合法输入 → 期望返回值
   - **参数校验失败**：非法/缺失输入 → 期望异常
   - **依赖异常**：mock 依赖抛异常 → 验证异常处理
   - **边界条件**：null、空集合、极值
3. 测试方法命名：`methodName_条件_期望结果`（如 `createOrder_validInput_returnsOrderId`）。
4. 使用 `@ExtendWith(MockitoExtension.class)` + `@Mock` + `@InjectMocks`。
5. 每个 `@Test` 方法使用 AAA 模式：Arrange（准备）→ Act（执行）→ Assert（断言）。
6. 断言使用 AssertJ 风格（`assertThat(result).isEqualTo(expected)`）。

## 输出结构

### 测试目标
- 方法签名
- 依赖列表

### 测试场景
1. 正常路径
2. 参数校验失败
3. 依赖异常
4. 边界条件

### 测试代码
```java
@ExtendWith(MockitoExtension.class)
class XxxTest {
    @Mock DependencyClass dependency;
    @InjectMocks TargetClass target;

    @Test
    void methodName_validInput_returnsExpected() {
        // Arrange
        // Act
        // Assert
    }

    @Test
    void methodName_invalidInput_throwsException() {
        // Arrange
        // Act & Assert
    }
}
```

### 覆盖建议
- 建议补充的场景（如有）$prompt$,
    system_prompt = $prompt$你是一个测试代码生成专家。根据图谱上下文，生成完整的 JUnit 5 单元测试代码。$prompt$,
    version = '2.0'
WHERE template_code = 'test-generation' AND is_active = true;

-- visualization
UPDATE lg_prompt_template
SET task_prompt = $prompt$## 图表类型选择指导
根据用户问题类型选择最合适的图表：
- 调用流程 → **时序图（sequenceDiagram）**
- 模块依赖 → **依赖图（graph LR）**
- 调用链路 → **调用链图（graph TD）**
- 数据流向 → **数据流图（graph LR）**
- 业务概览 → **业务链路图（graph LR）**

## 时序图（sequenceDiagram）
- 参与者：从调用链中提取的类/服务
- 消息：方法调用（含方法名和参数）
- 示例格式：
```mermaid
sequenceDiagram
    participant C as OrderController
    participant S as OrderService
    participant M as OrderMapper
    C->>S: create(OrderDTO)
    S->>M: insert(Order)
    M-->>S: orderId
    S-->>C: Result.ok(orderId)
```

## 依赖图（graph LR）
- 节点：Package/Module
- 边：DEPENDS_ON

## 调用链图（graph TD）
- 正向：入口方法 → 下游调用
- 反向：目标方法 → 上游调用方

## 数据流图（graph LR）
- Table → SqlStatement → Service → ApiEndpoint

## 业务链路图（graph LR）
- BusinessDomain → BusinessProcess → ApiEndpoint

## 输出要求
1. 生成 ```mermaid 代码块
2. 附文字说明：图表展示了什么
3. 如图谱数据不足，说明缺失部分
4. 如果用户问题涉及多种关系，可生成多个图表

## 输出格式
请以严格 JSON 格式输出：
```json
{
  "charts": [
    {
      "chartType": "sequenceDiagram",
      "title": "订单创建调用流程",
      "mermaidCode": "sequenceDiagram\n    participant C as OrderController\n    participant S as OrderService\n    C->>S: create(OrderDTO)\n    S-->>C: Result.ok(orderId)",
      "description": "展示了订单创建从 Controller 到 Service 的调用流程"
    }
  ],
  "missingData": "缺少 Mapper 层调用数据"
}
```

只输出 JSON，不要其他解释内容。$prompt$,
    system_prompt = $prompt$用户请求可视化图表。根据图谱反查结果，生成 Mermaid 代码块。$prompt$,
    version = '2.0'
WHERE template_code = 'visualization' AND is_active = true;

