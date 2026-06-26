# LegacyGraph 系统 LLM 接入改造方案

## 一、背景

基于 `/doc` 目录下的四份设计文档，对 LegacyGraph 老项目 AI 图谱理解平台进行 LLM 接入的整体思考。

## 二、现状分析

### 2.1 当前系统架构

```
输入层 -> 扫描层 -> 抽取层 -> 事实层 -> 图谱层 -> AI 层 -> 验证层 -> 展示层
```

### 2.2 已有 AI 能力设计

1. **CodeFactAgent**：抽取代码事实
2. **DBFactAgent**：抽取数据库事实
3. **DocUnderstandingAgent**：抽取文档业务事实
4. **FeatureMappingAgent**：匹配功能、页面、接口
5. **GraphMergeAgent**：合并节点、计算置信度
6. **TestCaseAgent**：生成测试用例
7. **AssertionAgent**：生成断言
8. **ReviewAgent**：生成待审核列表

### 2.3 当前 Prompt 模板
- 业务流程抽取 Prompt
- 功能映射 Prompt
- 测试用例生成 Prompt

---

## 三、LLM 接入整体架构设计

### 3.1 架构分层

```
┌─────────────────────────────────────────────────────────┐
│                   LLM 服务层                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐     │
│  │ Code LLM    │  │ Doc LLM     │  │ Graph LLM   │     │
│  │ Agent       │  │ Agent       │  │ Query       │     │
│  └─────────────┘  └─────────────┘  └─────────────┘     │
├─────────────────────────────────────────────────────────┤
│                   LLM 编排层                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐     │
│  │ Router      │  │ Memory      │  │ Tool        │     │
│  │ Chain       │  │ Chain       │  │ Chain       │     │
│  └─────────────┘  └─────────────┘  └─────────────┘     │
├─────────────────────────────────────────────────────────┤
│                   LLM 适配层                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐     │
│  │ OpenAI      │  │ Qwen        │  │ Local LLM   │     │
│  │ Adapter     │  │ Adapter     │  │ Adapter     │     │
│  └─────────────┘  └─────────────┘  └─────────────┘     │
├─────────────────────────────────────────────────────────┤
│                   LLM 基础设施层                         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐     │
│  │ Cache       │  │ Vector      │  │ Rate        │     │
│  │ Limit       │  │ Store       │  │ Limiter     │     │
│  └─────────────┘  └─────────────┘  └─────────────┘     │
└─────────────────────────────────────────────────────────┘
```

### 3.2 核心设计原则

1. **模型可插拔**：支持 OpenAI、通义千问、文心一言、本地部署模型
2. **Agent 可编排**：通过 LangGraph 或自研编排，支持灵活组合
3. **提示词可配置**：Prompt 模板外部化，支持热更新
4. **结果可验证**：所有 LLM 输出必须有证据链和置信度
5. **调用可追踪**：完整的调用日志、token 统计、成本监控
6. **失败可降级**：LLM 失败时自动降级到规则引擎
7. **安全可保障**：敏感数据脱敏、内容安全审核

---

## 四、LLM 增强点设计

### 4.1 代码理解增强

#### 4.1.1 代码语义理解

**当前问题**：
- 仅靠 AST 解析难以理解代码业务语义
- 动态 SQL、反射调用难以追踪
- 复杂业务逻辑无法自动归纳

**LLM 增强方案**：

```python
# 1. 方法语义理解
输入：Java 方法代码
输出：{
  "purpose": "方法业务含义",
  "businessDomain": "所属业务域",
  "keyLogic": "核心业务逻辑",
  "sideEffects": ["副作用1", "副作用2"],
  "exceptionScenarios": ["异常场景1", "异常场景2"],
  "confidence": 0.85
}

# 2. 动态 SQL 解析
输入：MyBatis 动态 SQL + 上下文
输出：{
  "normalizedSql": "标准化后的 SQL",
  "conditionBranches": [
    {
      "condition": "when status = 'DRAFT'",
      "tablesAffected": ["contract", "contract_history"],
      "operations": ["SELECT", "UPDATE"]
    }
  ],
  "tableRelations": ["contract 1:N contract_history"],
  "confidence": 0.78
}

# 3. 调用链补全
输入：已知调用链 + 缺失节点上下文
输出：{
  "missingLinks": [
    {
      "from": "ContractService",
      "to": "ApprovalService",
      "via": "反射调用 / 动态代理",
      "evidence": ["代码行号", "日志证据"],
      "confidence": 0.65
    }
  ]
}
```

#### 4.1.2 前端代码理解增强

```python
# 1. 页面功能自动归纳
输入：Vue 组件 + 模板 + script
输出：{
  "pagePurpose": "页面核心功能",
  "userOperations": [
    {
      "action": "提交审批",
      "trigger": "点击按钮",
      "apiCalled": "POST /api/approval/submit",
      "fieldsAffected": ["status", "approver"]
    }
  ],
  "formRules": ["必填校验规则", "联动规则"],
  "permissions": ["contract:create", "contract:submit"]
}

# 2. 组件关系推断
输入：组件目录结构 + import 关系
输出：{
  "componentHierarchy": "组件树",
  "dataFlow": "数据流方向",
  "eventFlow": "事件流方向",
  "sharedState": "共享状态"
}
```

### 4.2 文档理解增强

#### 4.2.1 多模态文档理解

```python
# 1. 表格抽取增强
输入：Word 表格 / Excel 表格
输出：{
  "tableType": "功能清单 / 角色权限 / 数据字典",
  "structuredData": {},
  "businessEntities": ["业务对象1", "业务对象2"],
  "confidence": 0.90
}

# 2. 流程图理解
输入：流程图图片 / Mermaid 文本
输出：{
  "startNode": "开始节点",
  "endNodes": ["结束节点1", "结束节点2"],
  "decisionPoints": ["判断节点1", "判断节点2"],
  "processFlow": "完整流程路径",
  "exceptionPaths": ["异常路径1", "异常路径2"]
}

# 3. 跨文档实体对齐
输入：多个文档中的同一业务术语
输出：{
  "standardTerm": "标准术语",
  "alias": ["别名1", "别名2"],
  "definitions": [
    {
      "sourceDoc": "文档1",
      "definition": "定义1"
    }
  ],
  "consistencyCheck": {
    "isConsistent": true,
    "conflicts": []
  }
}
```

#### 4.2.2 文档-代码自动对齐

```python
输入：
  - 文档中的功能描述
  - 前端页面
  - 后端接口
  - 数据库表

输出：{
  "mappings": [
    {
      "docFeature": "文档功能",
      "pages": ["页面1", "页面2"],
      "apis": ["接口1", "接口2"],
      "tables": ["表1", "表2"],
      "evidenceChain": ["证据1", "证据2"],
      "confidence": 0.88,
      "alignmentScore": 0.85
    }
  ],
  "unmappedItems": {
    "docsWithoutCode": ["文档功能1"],
    "codeWithoutDocs": ["接口1", "页面1"]
  }
}
```

### 4.3 图谱构建增强

#### 4.3.1 智能节点合并

```python
输入：
  - 多个疑似重复节点
  - 节点属性
  - 节点关系
  - 来源证据

输出：{
  "shouldMerge": true,
  "mergedNode": {
    "name": "合并后节点名",
    "type": "节点类型",
    "properties": "合并后属性"
  },
  "mergeReason": "合并理由",
  "confidence": 0.92,
  "riskAssessment": {
    "level": "LOW",
    "description": "合并风险低"
  }
}
```

#### 4.3.2 缺失关系推断

```python
输入：
  - 现有图谱
  - 孤立节点 A
  - 孤立节点 B
  - 上下文证据

输出：{
  "inferredRelations": [
    {
      "from": "节点A",
      "to": "节点B",
      "type": "CALLS / IMPLEMENTS / USES",
      "inferenceReason": "推断理由",
      "evidence": ["间接证据1", "间接证据2"],
      "confidence": 0.65,
      "needsReview": true
    }
  ],
  "alternativeHypotheses": [
    {
      "relationType": "备选关系类型",
      "confidence": 0.55
    }
  ]
}
```

#### 4.3.3 业务对象自动抽象

```python
输入：
  - 数据库表结构
  - 相关 SQL
  - 接口定义
  - 文档描述

输出：{
  "businessObjects": [
    {
      "name": "合同",
      "coreTables": ["contract", "contract_item"],
      "relatedApis": ["GET /api/contract", "POST /api/contract"],
      "lifecycle": ["创建", "提交", "审批", "归档"],
      "statusField": "contract.status",
      "keyAttributes": ["contract_no", "amount", "status"],
      "confidence": 0.85
    }
  ]
}
```

### 4.4 测试生成增强

#### 4.4.1 智能测试用例生成

```python
# 1. 边界值自动发现
输入：接口定义 + 字段类型 + 业务规则
输出：{
  "boundaryCases": [
    {
      "field": "amount",
      "testValues": [0, -1, 999999999, null],
      "expectedResults": ["通过", "拒绝", "拒绝", "拒绝"],
      "reason": "金额必须为正且不超过最大值"
    }
  ]
}

# 2. 业务场景自动组合
输入：业务流程 + 状态流转图
输出：{
  "scenarios": [
    {
      "name": "正常流程：创建->提交->审批通过->归档",
      "steps": ["步骤1", "步骤2", "步骤3"],
      "testData": {},
      "expectedStates": ["DRAFT", "SUBMITTED", "APPROVED", "ARCHIVED"]
    },
    {
      "name": "异常流程：创建->提交->驳回->修改->重新提交",
      "steps": ["步骤1", "步骤2", "步骤3"],
      "expectedStates": ["DRAFT", "SUBMITTED", "REJECTED", "DRAFT", "SUBMITTED"]
    }
  ]
}

# 3. 数据库断言自动生成
输入：SQL 读写关系 + 表结构
输出：{
  "assertions": [
    {
      "type": "ROW_COUNT_CHANGE",
      "table": "contract",
      "expectedChange": "+1",
      "sql": "SELECT COUNT(*) FROM contract WHERE ..."
    },
    {
      "type": "FIELD_VALUE_CHANGE",
      "table": "contract",
      "field": "status",
      "from": "DRAFT",
      "to": "SUBMITTED"
    }
  ]
}
```

#### 4.4.2 测试数据智能生成

```python
输入：
  - 字段类型和约束
  - 业务规则
  - 现有数据样本

输出：{
  "testData": {
    "valid": [
      {
        "field": "contract_no",
        "value": "HT20240001",
        "format": "HT + 年份 + 序号",
        "uniqueness": "唯一"
      }
    ],
    "invalid": [
      {
        "field": "contract_no",
        "value": "",
        "reason": "不能为空"
      }
    ],
    "edge": [
      {
        "field": "amount",
        "value": 0.01,
        "reason": "最小金额"
      }
    ]
  }
}
```

### 4.5 人工审核增强

#### 4.5.1 智能审核建议

```python
输入：待审核节点 + 所有关联证据
输出：{
  "reviewSummary": "审核摘要",
  "recommendation": "APPROVE / REJECT / NEED_MORE_INFO",
  "confidence": 0.85,
  "supportingEvidence": ["强证据1", "强证据2"],
  "contradictingEvidence": ["矛盾证据1"],
  "missingEvidence": ["缺失证据1"],
  "suggestedActions": [
    "建议补充XX文档",
    "建议核对XX代码"
  ]
}
```

#### 4.5.2 审核辅助问答

```
用户问题：这个业务流程为什么置信度这么低？

LLM 回答：
{
  "reason": "有三个因素导致置信度低：",
  "details": [
    "1. 产品文档中描述的流程与代码实现存在两处不一致（证据ID: E101, E102）",
    "2. 前端页面调用的接口路径与后端实现不完全匹配（证据ID: E203）",
    "3. 缺少审批通过后的状态变更测试用例验证"
  ],
  "suggestions": [
    "建议核对最新版本的产品文档",
    "建议执行端到端测试验证实际行为"
  ]
}
```

### 4.6 自然语言查询增强

#### 4.6.1 图谱问答

```
用户问题："创建合同这个功能会读写哪些数据库表？"

LLM 处理流程：
1. 解析问题，识别意图：功能 -> 表关系查询
2. 在图谱中搜索"创建合同"相关节点
3. 遍历关系链：Feature -> Page -> Button -> Api -> Controller -> Service -> Mapper -> SQL -> Table
4. 生成自然语言回答

输出：
{
  "answer": "创建合同功能会读写以下数据库表：",
  "tables": [
    {
      "name": "contract",
      "operations": ["INSERT", "UPDATE"],
      "evidence": "ContractMapper.insert, ContractMapper.updateStatus"
    },
    {
      "name": "contract_item",
      "operations": ["INSERT"],
      "evidence": "ContractItemMapper.batchInsert"
    }
  ],
  "evidenceChain": ["完整证据链路"],
  "visualizationQuery": "图谱查询语句"
}
```

#### 4.6.2 影响分析查询

```
用户问题："如果修改 contract 表的 status 字段，会影响哪些功能？"

输出：
{
  "impactedFeatures": [
    {
      "feature": "提交审批",
      "impactLevel": "HIGH",
      "reason": "提交时会修改 status 从 DRAFT 到 SUBMITTED"
    },
    {
      "feature": "审批通过",
      "impactLevel": "HIGH",
      "reason": "审批通过时会修改 status 从 SUBMITTED 到 APPROVED"
    }
  ],
  "impactedApis": ["POST /api/contract/submit", "POST /api/contract/approve"],
  "impactedPages": ["合同列表页", "合同详情页"],
  "testCasesToReview": ["test_contract_submit", "test_contract_approve"],
  "migrationSuggestions": "迁移建议"
}
```

---

## 五、LLM Agent 详细设计

### 5.1 Agent 类型分层

```
一级 Agent（高置信度，直接入库）：
  ├─ CodeStructureAgent      # 代码结构抽取
  ├─ DBSchemaAgent           # 数据库结构抽取
  └─ ApiSpecAgent            # 接口规范抽取

二级 Agent（中置信度，需审核）：
  ├─ BusinessNamingAgent     # 业务命名标准化
  ├─ RelationInferenceAgent  # 关系推断
  └─ FeatureMappingAgent     # 功能映射

三级 Agent（低置信度，必须审核）：
  ├─ DocUnderstandingAgent   # 文档深度理解
  ├─ BusinessLogicAgent      # 业务逻辑抽象
  ├─ ProcessMiningAgent      # 流程挖掘
  └─ MigrationAdvisorAgent   # 迁移建议
```

### 5.2 Agent 执行流水线

```
Step 1: 结构化抽取（规则引擎 + LLM 辅助）
  输入：原始文件
  输出：结构化事实
  置信度要求：>= 0.9

Step 2: 关系建立（规则为主，LLM 辅助）
  输入：结构化事实
  输出：初步关系
  置信度要求：>= 0.8

Step 3: 语义对齐（LLM 为主）
  输入：跨来源事实
  输出：对齐后的统一实体
  置信度要求：>= 0.7

Step 4: 业务抽象（纯 LLM）
  输入：技术实现细节
  输出：业务层抽象
  置信度要求：>= 0.6
```

### 5.3 Agent 协作模式

#### 5.3.1 监督模式

```
CoordinatorAgent
    ↓ 分发任务
CodeAgent  DocAgent  DBAgent
    ↓ 各自产出
    └───> MergeAgent 合并结果
            ↓
        ReviewAgent 质量检查
            ↓
        GraphBuilder 入库
```

#### 5.3.2 辩论模式

```
对于高争议节点：
  Agent A：提出假设 + 证据
  Agent B：反驳 + 反证
  Agent C：中立验证
  Coordinator：综合判断，给出最终置信度
```

---

## 六、Prompt 工程体系

### 6.1 Prompt 分层设计

```
┌─────────────────────────────────────┐
│     System Prompt（系统层）          │
│  - 角色设定                          │
│  - 输出规范                          │
│  - 约束条件                          │
├─────────────────────────────────────┤
│     Domain Prompt（领域层）          │
│  - 业务术语定义                      │
│  - 项目背景                          │
│  - 已知模式                          │
├─────────────────────────────────────┤
│     Task Prompt（任务层）            │
│  - 具体任务描述                      │
│  - 输入数据                          │
│  - 输出格式                          │
├─────────────────────────────────────┤
│     Examples（示例层）               │
│  - 正确示例                          │
│  - 错误示例                          │
│  - 边界情况                          │
└─────────────────────────────────────┘
```

### 6.2 Prompt 模板仓库

#### 6.2.1 代码理解类

```yaml
template_id: code_001
name: Java 方法业务语义理解
version: 1.0
system_prompt: |
  你是一位有10年经验的企业应用架构师。
  请分析给定的 Java 方法，用业务语言描述其功能。
  
  要求：
  1. 只基于代码内容分析，不要猜测
  2. 给出置信度分数（0-1）
  3. 输出严格 JSON 格式
  
task_prompt: |
  请分析以下 Java 方法：
  
  类名：{{className}}
  方法名：{{methodName}}
  方法代码：
  {{methodCode}}
  
  上下文：
  - 所属模块：{{module}}
  - 相关表：{{relatedTables}}
  
  输出 JSON：
  {
    "purpose": "方法业务目的",
    "businessDomain": "业务域",
    "mainOperations": ["操作1", "操作2"],
    "sideEffects": ["副作用1"],
    "confidence": 0.85,
    "uncertainReasons": ["不确定原因"]
  }

examples:
  - input: "createContract 方法代码..."
    output: '{"purpose": "创建合同", "businessDomain": "合同管理", ...}'
```

#### 6.2.2 文档理解类

```yaml
template_id: doc_001
name: 业务流程抽取
version: 1.0
system_prompt: |
  你是资深业务分析师。
  请从产品文档中抽取结构化的业务流程。
  
  注意：
  - 只抽取文档中明确描述的内容
  - 用证据链支持每个结论
  - 每个流程必须有开始和结束状态

task_prompt: |
  文档片段：
  {{documentContent}}
  
  文档元数据：
  - 文档名称：{{docName}}
  - 章节：{{section}}
  
  请输出：
  {
    "processes": [
      {
        "name": "流程名称",
        "actor": ["参与者1"],
        "preconditions": ["前置条件"],
        "steps": ["步骤1"],
        "postconditions": ["后置条件"],
        "statusTransitions": ["状态1 -> 状态2"],
        "evidence": ["原文引用1"]
      }
    ]
  }
```

#### 6.2.3 图谱构建类

```yaml
template_id: graph_001
name: 节点相似度判断
version: 1.0
system_prompt: |
  你是图谱构建专家。
  请判断两个节点是否指向同一个业务实体。
  
  判断维度：
  1. 名称语义相似度
  2. 属性重叠度
  3. 关系相似性
  4. 来源一致性

task_prompt: |
  节点A：
  {{nodeA}}
  
  节点B：
  {{nodeB}}
  
  上下文：
  - 项目：{{project}}
  - 已有节点：{{existingNodes}}
  
  输出：
  {
    "shouldMerge": true/false,
    "similarityScore": 0.85,
    "mergeStrategy": "A为主/B为主/合并属性",
    "evidence": ["理由1", "理由2"],
    "confidence": 0.78
  }
```

### 6.3 Prompt 版本管理

- 每个 Prompt 模板有唯一 ID 和版本号
- 修改需要经过评审流程
- 支持 A/B 测试不同版本效果
- 记录每个版本的准确率统计

---

## 七、基础设施设计

### 7.1 LLM 网关

```
功能：
  - 多模型路由：根据任务类型选择最优模型
  - 流量控制：QPS 限制、熔断、降级
  - 缓存：相同输入直接返回缓存结果
  - 重试：失败自动重试（指数退避）
  - 审计：完整记录每次调用的输入、输出、token、耗时

路由策略示例：
  - 代码结构解析：gpt-3.5-turbo（快、便宜）
  - 复杂业务理解：gpt-4（准、贵）
  - 批量简单任务：本地部署模型
```

### 7.2 向量知识库

```
存储内容：
  - 代码片段向量
  - 文档片段向量
  - 业务术语向量
  - 历史审核记录向量

检索场景：
  - 相似代码参考
  - 相关文档查找
  - 历史审核案例检索
  - 业务术语自动补全
```

### 7.3 结果缓存

```
缓存键设计：
  md5(template_id + version + input_normalized)

缓存层级：
  L1：内存缓存（热点数据，5分钟）
  L2：Redis 缓存（1小时）
  L3：数据库持久化（永久，用于审计）
```

### 7.4 Token 与成本监控

```
统计维度：
  - 按项目：每个项目的 token 消耗
  - 按 Agent：每个 Agent 类型的 token 消耗
  - 按模型：不同模型的 token 消耗
  - 按用户：每个用户的调用成本

预警机制：
  - 日消耗超过阈值告警
  - 单任务 token 异常告警
  - 失败率超过阈值告警
```

---

## 八、质量保障体系

### 8.1 LLM 输出质量评估

#### 8.1.1 自动校验

```
校验维度：
  1. 格式校验：是否符合 JSON 规范
  2. 字段完整：必填字段是否存在
  3. 范围校验：置信度是否在 0-1 之间
  4. 引用校验：证据是否可追溯到原始来源
  5. 逻辑自洽：输出内容是否自相矛盾
```

#### 8.1.2 人工标注评估

```
定期抽样评估：
  - 每周抽取 100 个 LLM 输出
  - 人工标注正确性
  - 计算精确率、召回率、F1
  - 识别系统性偏差，优化 Prompt
```

### 8.2 置信度动态调整

```
初始置信度：LLM 输出的原始置信度

事后调整：
  + 测试验证通过：+0.1
  + 人工审核通过：+0.15
  - 测试发现错误：-0.2
  - 人工审核驳回：-0.25

置信度阈值：
  >= 0.9：自动入库，无需审核
  0.7-0.9：进入快速审核队列
  0.5-0.7：进入普通审核队列
  < 0.5：标记为待验证，不展示在主视图
```

---

## 九、分阶段落地计划

### 9.1 第一阶段（2 周）：LLM 基础设施 + 核心能力

**目标**：搭建 LLM 接入基础设施，替换现有简单 Prompt

| 任务 | 说明 |
|------|------|
| LLM 网关开发 | 支持多模型、缓存、重试、监控 |
| Prompt 模板管理 | 模板化、版本化、热更新 |
| CodeFactAgent 增强 | 代码语义理解能力 |
| DocUnderstandingAgent 增强 | 文档深度理解能力 |
| 输出质量校验框架 | 自动校验 LLM 输出格式和内容 |

**产出**：
- LLM 服务可用
- 代码和文档理解质量提升 30%

### 9.2 第二阶段（3 周）：图谱构建增强

**目标**：用 LLM 增强图谱构建，减少人工工作量

| 任务 | 说明 |
|------|------|
| 智能节点合并 | LLM 判断重复节点 |
| 缺失关系推断 | LLM 推断可能的关系 |
| 业务对象自动抽象 | 从技术实现抽象业务对象 |
| 置信度智能计算 | 多因素综合置信度 |
| 审核建议生成 | 为人工审核提供智能建议 |

**产出**：
- 图谱构建自动化率提升 40%
- 人工审核工作量减少 50%

### 9.3 第三阶段（2 周）：测试生成增强

**目标**：智能生成高质量测试用例

| 任务 | 说明 |
|------|------|
| 边界值自动发现 | LLM 发现边界测试点 |
| 业务场景组合测试 | 生成端到端业务流程测试 |
| 智能测试数据生成 | 符合业务规则的测试数据 |
| 数据库断言增强 | 自动生成完整 DB 断言 |
| 测试结果智能分析 | 自动定位失败原因 |

**产出**：
- 测试用例覆盖率提升 50%
- 测试生成效率提升 100%

### 9.4 第四阶段（2 周）：自然语言交互

**目标**：支持用户用自然语言查询图谱

| 任务 | 说明 |
|------|------|
| 图谱问答引擎 | 自然语言问题转图谱查询 |
| 影响分析查询 | 修改某对象的影响范围分析 |
| 智能解释 | 用业务语言解释图谱 |
| 迁移建议生成 | 基于图谱生成迁移建议 |

**产出**：
- 用户查询响应时间 < 3秒
- 问题解决率 > 85%

### 9.5 第五阶段（2 周）：Agent 编排与自治

**目标**：实现 Agent 协作和自我优化

| 任务 | 说明 |
|------|------|
| Agent 编排引擎 | 多 Agent 协作流程 |
| 质量反馈闭环 | 审核结果回流优化模型 |
| 自动 Prompt 优化 | 根据效果自动优化 Prompt |
| 模型 A/B 测试 | 多模型效果对比 |

**产出**：
- 系统具备自我优化能力
- 整体准确率持续提升

---

## 十、风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| LLM 幻觉生成错误事实 | 图谱污染 | 1. 所有输出必须有证据链<br>2. 低置信度强制人工审核<br>3. 测试验证回写机制 |
| LLM 调用成本过高 | 成本失控 | 1. 分层模型路由（简单任务用小模型）<br>2. 结果缓存复用<br>3. Token 预算控制 |
| LLM 响应速度慢 | 用户体验差 | 1. 异步任务队列<br>2. 批量处理<br>3. 结果预生成 |
| 敏感数据泄露 | 安全合规 | 1. 代码脱敏（密钥、密码、IP）<br>2. 数据白名单机制<br>3. 仅传必要上下文 |
| Prompt 维护成本高 | 难管理 | 1. 模板化、版本化<br>2. 效果自动评估<br>3. Prompt 工程师专人维护 |
| 模型更新影响效果 | 质量波动 | 1. 模型版本锁定<br>2. 新版本灰度测试<br>3. 效果监控告警 |

---

## 十一、成功衡量指标

### 11.1 效率指标

| 指标 | 目标值 |
|------|--------|
| 图谱构建时间 | 减少 60% |
| 人工审核工作量 | 减少 50% |
| 测试用例生成时间 | 减少 70% |
| 用户查询响应时间 | < 3 秒 |

### 11.2 质量指标

| 指标 | 目标值 |
|------|--------|
| 代码理解准确率 | > 90% |
| 文档理解准确率 | > 85% |
| 功能映射准确率 | > 80% |
| 测试用例有效率 | > 75% |

### 11.3 成本指标

| 指标 | 目标值 |
|------|--------|
| 单次图谱构建 LLM 成本 | < 50 元 |
| LLM 调用成功率 | > 99% |
| 缓存命中率 | > 60% |

---

## 十二、总结与建议

### 12.1 核心观点

1. **LLM 不是万能的**：它最擅长语义理解、归纳、推断，不擅长精确的结构解析
2. **证据链是生命线**：所有 LLM 输出必须可追溯、可验证、可推翻
3. **人机协作是最佳模式**：LLM 负责初稿和建议，人负责最终确认
4. **质量闭环是关键**：测试结果和审核意见必须回流优化系统

### 12.2 开工建议

**第一优先级（立即开始）**：
1. 搭建 LLM 网关和基础设施
2. 将现有 Prompt 模板化管理
3. 为 CodeFactAgent 和 DocUnderstandingAgent 接入 LLM

**第二优先级（1个月内）**：
1. 实现智能节点合并和关系推断
2. 增强测试用例生成能力
3. 建立质量评估和反馈闭环

**第三优先级（3个月内）**：
1. 实现自然语言图谱查询
2. Agent 编排和自治能力
3. 完整的迁移建议生成

### 12.3 一句话建议

> **先用 LLM 把人从重复劳动中解放出来，让人专注于需要判断力的关键决策。**
