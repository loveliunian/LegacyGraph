# QA 问答能力加强实施 Spec

## Why

当前 LegacyGraph 的 QA 问答模块基于增强版 GraphRAG（pgvector 语义检索 + Neo4j 图谱多跳扩展 + LLM 流式生成），只能稳定回答 A 类（变更影响分析）中"加字段"这类**表级、lg_ 前缀、字段名无关**的窄场景问题。对"需求实现方案"、"权限与角色"、"业务流程操作"、"数据血缘"、"测试影响"、"异常排查"、"架构依赖"、"接口契约"、"配置项"等十大类高频问题，要么走通用 RAG 碰运气、要么完全不能回答。

根本原因有三：
1. **图谱已扫但未建边**：Column、TestCase、BusinessProcess、Role、ConfigItem 等节点都已扫描入库，但关键的 GRANTS、BELONGS_TO、CONTAINS、DATA_FLOW 等边缺失或定义后零使用，节点成孤岛
2. **节点 properties 关键字段丢弃**：ApiFact 抽取了 params/responseType、ConfigItemFact 抽取了 value/defaultValue，但 GraphBuilder 创建节点时只存 displayName，契约信息和配置值丢失
3. **专用意图与链路缺失**：QueryIntent 只有 7 种，对 B/C/D/E/F 类问题没有专用分支，全走通用 GraphRAG，LLM 拿不到结构化证据

本 Spec 统一规划 34 项加强（P0~P6），分 7 个阶段递进实施，目标让 QA 问答能覆盖 10 大类问题、34 个具体场景。

## What Changes

### 优先级 P0：让变更影响问答"能跑通"（A 类基础）
- 放开 `ChangeImpactQuestionParser.TABLE_PATTERN` 的 `lg_` 前缀限制，支持任意表名
- 新增 `Neo4jGraphDao.findNodeByName` 精确查询，去掉 `resolveTableNodeId` 的 200 表上限
- 解析不出表名时从图谱 `queryNodes("Table")` 取全量 nodeName 做模糊匹配兜底
- `GraphPathReadModel.getTableImpact` 同步改造

### 优先级 P1：字段级反查精度（A 类提升）
- 新增 `TraversalDirection.COLUMN_REVERSE`，以 Column 节点为起点反查
- `EnhancedQaAgent` 新增 `resolveColumnNodeId`，columnName 非空时优先走 Column 起点
- 统一 Column nodeKey 为 `{tableName.lower}.{column.lower}`，让 DB schema 和 SQL 解析 MERGE 同一节点

### 优先级 P2：前端链路与输出结构（A 类完善）
- `TraversalDirection.TABLE_REVERSE.edgeTypes()` 追加 `CALLS`，反查链路延伸到 Page/Button
- `change-impact` prompt 模板对齐分层执行步骤结构

### 优先级 P3：需求实现方案能力（B 类新建）
- 新增 `IMPLEMENTATION_PLAN` 意图
- 新建 `implementation-plan.txt` prompt 模板（需求分解+分层方案+可复用组件+实施步骤）
- 新增 `ProjectConventionIngestService`，扫描后把技术栈/分层规范/命名约定向量化（chunkType=PROJECT_CONVENTION）
- 扫描时标记 reusable 组件（properties.reusable=true，统计被继承次数）
- `QueryRewriter` 需求拆解改写（分层检索变体）
- `EnhancedQaAgent` 新增 IMPLEMENTATION_PLAN 处理分支

### 优先级 P4：权限与角色问答能力（C 类新建）
- 新增 `PERMISSION_LOOKUP` 意图
- 新增 `GRANTS` 边类型，打通 `Role --GRANTS--> Permission`
- 新增 `User` 节点类型和 `ASSIGNED_TO` 边，打通 `Role --ASSIGNED_TO--> User`
- 修复 `@PreAuthorize` 重复扫描语义冲突（统一由 RbacRoleExtractor 处理 SpEL）
- 统一前后端 Permission nodeKey（小写化原值）
- `EnhancedQaAgent` 新增 PERMISSION_LOOKUP 处理链路

### 优先级 P5：业务流程操作问答能力（D 类新建）
- 新增 `PROCEDURE_LOOKUP` 意图
- 新增 `RequiredDocument` 节点类型和 `REQUIRES_DOCUMENT` 边
- `DocUnderstandingAgent.BusinessProcess` 新增 `materials` 字段，LLM 抽取资料清单
- `ApiFact` 新增 `summary` 字段，解析 `@Operation`/`@ApiOperation` Swagger 注解
- 打通 `BusinessProcess ↔ BusinessDomain ↔ ApiEndpoint` 链路（移除孤立逻辑、建 CONTAINS/IMPLEMENTS 边）
- 新建 `procedure-guide.txt` prompt 模板
- `QueryRewriter` 流程多维拆解改写
- `EnhancedQaAgent` 新增 PROCEDURE_LOOKUP 处理链路

### 优先级 P6：六大通用方向加强（E~J 类）
- **E 类数据血缘**：新增 `TABLE_FORWARD` 正向遍历方向（复用已有 OUTBOUND 基础设施）、新增 `DATA_LINEAGE` 意图
- **F 类测试影响**：VERIFIED_BY 边下沉到方法级、新增 `TEST_IMPACT` 意图
- **G 类异常排查**：新增 `Exception`/`LogPoint` 节点、新增 `THROWS`/`CATCHES`/`LOGS` 边、新建 `ExceptionExtractor`+`ExceptionAdapter`
- **H 类架构依赖**：新增 `Package` 节点、新增 `BELONGS_TO` 边、接线 `DEPENDS_ON` 边（扫描 import 语句）
- **I 类接口契约**：`GraphBuilder.buildApiNodes` 创建 ApiEndpoint 节点时补 `.properties(params/requestBody/responseType)`
- **J 类配置项**：`GraphBuilder.buildConfigItemGraph` 创建 ConfigItem 节点时补 `.properties(value/defaultValue/className/fieldName)`

## Impact

- **Affected specs**: `doc/QA变更影响问答加强清单.md`（v5.0，作为本 Spec 的前置清单）
- **Affected code**:
  - `agent/EnhancedQaAgent.java` — 新增 5 个意图处理分支（CHANGE_IMPACT 已有 + IMPLEMENTATION_PLAN/PERMISSION_LOOKUP/PROCEDURE_LOOKUP/DATA_LINEAGE/TEST_IMPACT）
  - `agent/QueryIntent.java` — 新增 5 个枚举值
  - `common/NodeType.java` — 新增 4 种节点（User/RequiredDocument/Exception/LogPoint/Package）
  - `common/EdgeType.java` — 新增 8 种边（GRANTS/ASSIGNED_TO/REQUIRES_DOCUMENT/THROWS/CATCHES/LOGS/BELONGS_TO/DATA_FLOW）
  - `common/TraversalDirection.java` — 新增 2 个方向（COLUMN_REVERSE/TABLE_FORWARD）
  - `builder/GraphBuilder.java` — 修改 4 处 properties 写入 + 新增 3 个 buildXxxGraph 方法
  - `builder/BusinessGraphBuilder.java` — 移除孤立逻辑 + 建边
  - `extractors/JavaControllerExtractor.java` — 新增 Swagger 解析 + 协调 @PreAuthorize 处理
  - `extractors/RbacRoleExtractor.java` — 解析 SpEL 建 GRANTS 边
  - 新建文件：`ExceptionExtractor.java`、`ExceptionAdapter.java`、`ProjectConventionIngestService.java`、`implementation-plan.txt`、`procedure-guide.txt`
- **BREAKING**: P1-4 统一 Column nodeKey 后，旧 schema 前缀 Column 节点会变孤立，需重新扫描或清理 STALE 节点
- **BREAKING**: P4-16 调整 @PreAuthorize 处理归属后，旧 Permission 节点可能重复，需扫描后 MERGE

## ADDED Requirements

### Requirement: 变更影响问答字段级精度
系统 SHALL 支持以 Column 节点为起点反查变更影响子图，只返回真正引用该字段的 SQL/Mapper/Service/Controller。

#### Scenario: 加不同字段返回不同影响子图
- **WHEN** 用户问"给 lg_order 加 status 字段"
- **AND** 用户问"给 lg_order 加 priority 字段"
- **THEN** 两次返回不同的影响子图
- **AND** status 子图只包含真正引用 status 列的 SqlStatement/Mapper/Service

#### Scenario: 非 lg_ 前缀表名识别
- **WHEN** 用户问"给 user 表加 phone 字段"
- **THEN** 识别 tableName=user
- **AND** 触发变更影响链路

#### Scenario: 200+ 表项目定位
- **GIVEN** 项目有 250 张表
- **WHEN** 查询第 201 张表的影响
- **THEN** 仍能正确定位目标表节点

### Requirement: 需求实现方案生成
系统 SHALL 对"做一个 XX 功能"类问题生成结构化分层实现方案。

#### Scenario: 生成分层方案
- **WHEN** 用户问"做一个订单导出功能"
- **THEN** 识别为 IMPLEMENTATION_PLAN 意图
- **AND** 输出含需求分解、数据库层/实体层/Mapper层/Service层/Controller层/前端层、可复用组件、风险注意事项、实施步骤

#### Scenario: 可复用组件召回
- **WHEN** 生成方案时
- **THEN** 列出 BaseEntity/PageResult/Result 等可复用基类
- **AND** 标注 reusable=true 的节点 boost 权重

### Requirement: 权限与角色问答
系统 SHALL 打通 Role↔Permission↔User 链路，回答"XX 操作需要什么权限、谁能办"。

#### Scenario: 权限链路反查
- **WHEN** 用户问"新建任务需要什么权限"
- **THEN** 输出权限标识（如 task:create）
- **AND** 输出授予该权限的角色列表
- **AND** 输出分配了该角色的用户列表

#### Scenario: 前后端权限合并
- **WHEN** 前端 `v-permission="task:create"` 和后端 `@PreAuthorize("hasAuthority('task:create')")`
- **THEN** 生成同一 Permission 节点

### Requirement: 业务流程操作问答
系统 SHALL 回答"在 XX 里开户如何操作、提交哪些资料"类问题。

#### Scenario: 输出结构化操作指南
- **WHEN** 用户问"在系统里开户如何操作"
- **THEN** 识别为 PROCEDURE_LOOKUP 意图
- **AND** 输出含操作步骤、所需资料清单、相关接口、注意事项

#### Scenario: 资料清单反查
- **WHEN** 用户问"开户需要提交哪些资料"
- **THEN** 反查到 RequiredDocument 节点列表

### Requirement: 数据血缘正向遍历
系统 SHALL 支持正向遍历 Table→下游消费方，回答"数据流到哪些报表"。

#### Scenario: 正向数据流
- **WHEN** 用户问"lg_order 表的数据流到哪些报表"
- **THEN** 正向遍历到下游 SqlStatement/Service/ApiEndpoint

### Requirement: 测试影响方法级精度
系统 SHALL 支持方法级 TestCase 反查，回答"改了 XX 方法要跑哪些测试"。

#### Scenario: 方法级 TestCase 反查
- **WHEN** 用户问"改了 orderService.create 方法要跑哪些测试"
- **THEN** 反查到方法级 TestCase 节点列表

### Requirement: 异常与日志排查
系统 SHALL 扫描 Exception 和 LogPoint 节点，回答"XX 接口可能抛什么异常、日志在哪里"。

#### Scenario: 异常反查
- **WHEN** 用户问"下单接口可能抛什么异常"
- **THEN** 反查到 Exception 节点列表

#### Scenario: 日志点反查
- **WHEN** 用户问"XX 接口报 500 怎么排查"
- **THEN** 反查到 LogPoint 节点和 sourcePath

### Requirement: 架构与依赖理解
系统 SHALL 扫描 Package 节点和 DEPENDS_ON 边，回答"系统有哪些模块、模块间依赖关系"。

#### Scenario: 模块依赖反查
- **WHEN** 用户问"XX 模块依赖哪些模块"
- **THEN** 反查到 DEPENDS_ON 边指向的 Package 节点

### Requirement: 接口契约查询
系统 SHALL 在 ApiEndpoint 节点 properties 中存储契约信息，回答"XX 接口的参数是什么、返回什么格式"。

#### Scenario: 接口参数查询
- **WHEN** 用户问"POST /api/order/create 接口的参数是什么"
- **THEN** 从 ApiEndpoint.properties 读出 params 字段

### Requirement: 配置项查询
系统 SHALL 在 ConfigItem 节点 properties 中存储 value/defaultValue，回答"XX 配置项的值是什么"。

#### Scenario: 配置值查询
- **WHEN** 用户问"order.timeout 配置项的值是什么"
- **THEN** 从 ConfigItem.properties 读出 value 和 defaultValue

## MODIFIED Requirements

### Requirement: 变更影响问答
原有变更影响链路（Table 起点、INBOUND、lg_ 前缀）增强为：支持任意表名、字段级 Column 起点、延伸到前端 Page/Button。

### Requirement: 意图分类
QueryIntent 从 7 种扩展到 12 种（新增 IMPLEMENTATION_PLAN/PERMISSION_LOOKUP/PROCEDURE_LOOKUP/DATA_LINEAGE/TEST_IMPACT），intent-classifier.txt 同步增加识别规则。

### Requirement: 图谱节点 properties
ApiEndpoint 节点补写 params/requestBody/responseType；ConfigItem 节点补写 value/defaultValue；Class 节点补写 reusable/reuseType/usageCount。

## REMOVED Requirements

### Requirement: BusinessProcess 孤立节点
**Reason**: P5-22 打通 BusinessProcess↔BusinessDomain↔ApiEndpoint 链路，不再保持孤立
**Migration**: 移除 `BusinessGraphBuilder.java:138-139` 的孤立注释和逻辑，建 CONTAINS/IMPLEMENTS 边
