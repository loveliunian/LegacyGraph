# Tasks

## 阶段一：P0 变更影响问答跑通（A 类基础）

- [x] Task 1: 放开表名识别 lg_ 前缀限制（P0-1）
  - [x] SubTask 1.1: 修改 `ChangeImpactQuestionParser.TABLE_PATTERN` 正则为 `(?:表|table)\s+([a-zA-Z_][a-zA-Z0-9_]*)`
  - [x] SubTask 1.2: 在 `parseByRegex` 增加图谱兜底逻辑，解析不出表名时从 `queryNodes("Table")` 模糊匹配
  - [x] SubTask 1.3: 编写测试验证非 lg_ 表名能识别

- [x] Task 2: resolveTableNodeId 查询效率与上限（P0-2）
  - [x] SubTask 2.1: 在 `Neo4jGraphDao` 新增 `findNodeByName(projectId, versionId, nodeType, nodeName)` 方法
  - [x] SubTask 2.2: `EnhancedQaAgent.resolveTableNodeId` 改用 `findNodeByName`，去掉 200 limit
  - [x] SubTask 2.3: `GraphPathReadModel.getTableImpact` 同步改造
  - [x] SubTask 2.4: 编写测试验证 200+ 表项目能定位

## 阶段二：P1 字段级反查精度（A 类提升）

- [x] Task 3: 字段级反查链路（Column 起点）（P1-3）
  - [x] SubTask 3.1: `TraversalDirection` 新增 `COLUMN_REVERSE`，edgeTypes 含 READS/WRITES/EXECUTES/CALLS/HANDLED_BY
  - [x] SubTask 3.2: `EnhancedQaAgent` 新增 `resolveColumnNodeId(projectId, versionId, tableName, columnName)`
  - [x] SubTask 3.3: `answerStream` 变更影响分支增加 Column 起点逻辑（columnName 非空时优先）
  - [x] SubTask 3.4: 编写测试验证加不同字段返回不同影响子图

- [x] Task 4: Column nodeKey 统一（P1-4）
  - [x] SubTask 4.1: `GraphBuilder.java:548` 的 buildDatabaseGraph 中 Column nodeKey 改为 `{tableName.lower}.{column.lower}`
  - [x] SubTask 4.2: 验证 `findOrCreateColumnNode`（:904-925）已是此格式
  - [x] SubTask 4.3: 编写测试验证 DB schema 和 SQL 解析 MERGE 同一 Column 节点

## 阶段三：P2 前端链路与输出结构（A 类完善）

- [x] Task 5: 前端影响链路补全（P2-5）
  - [x] SubTask 5.1: `TraversalDirection.TABLE_REVERSE.edgeTypes()` 已含 CALLS（核查确认已存在）
  - [x] SubTask 5.2: ImpactSubgraphService 深度上限从 4 提到 5，确保第 5 跳 Page/Button 可达

- [x] Task 6: LLM 输出执行步骤结构（P2-6）
  - [x] SubTask 6.1: 在 `change-impact` prompt 模板追加分层输出结构（受影响清单+执行步骤+风险等级）
  - [x] SubTask 6.2: 新增 V47 迁移脚本同步 DB 模板
  - [x] SubTask 6.3: 验证 LLM 稳定输出分层结构

## 阶段四：P3 需求实现方案能力（B 类新建）

- [x] Task 7: 新增 IMPLEMENTATION_PLAN 意图（P3-7）
  - [x] SubTask 7.1: `QueryIntent.java` 新增 `IMPLEMENTATION_PLAN` 枚举值
  - [x] SubTask 7.2: `intent-classifier.txt` 增加识别规则（"做一个/实现/开发/新增 XX 功能/需求/模块"）
  - [x] SubTask 7.3: 编写测试验证"做一个订单导出功能"被识别为 IMPLEMENTATION_PLAN

- [x] Task 8: 方案生成 Prompt 模板（P3-8）
  - [x] SubTask 8.1: 新建 `prompts/implementation-plan.txt`（需求分解+分层方案+可复用组件+实施步骤结构）
  - [x] SubTask 8.2: 数据库迁移脚本 V48 新增 `template_code=implementation-plan` 种子数据
  - [x] SubTask 8.3: 验证 LLM 稳定输出分层方案

- [x] Task 9: 项目约定知识入库（P3-9）
  - [x] SubTask 9.1: 新建 `ProjectConventionIngestService`，把 Project.techStack/分层规范/命名约定向量化（chunkType=PROJECT_CONVENTION）
  - [x] SubTask 9.2: `ProjectScanner` 扫描完成后追加触发
  - [x] SubTask 9.3: 验证 QA 问"项目用什么技术栈"能召回 PROJECT_CONVENTION 向量

- [x] Task 10: 可复用组件标记（P3-10）
  - [x] SubTask 10.1: 新建 `ReusableComponentMarker`，统计每个类被继承次数（EXTENDS 边）
  - [x] SubTask 10.2: 超过阈值的类在 `GraphNode.properties` 标记 `reusable=true/reuseType/usageCount`
  - [x] SubTask 10.3: `HybridRetrievalService` 新增 reusable 节点 boost 权重策略
  - [x] SubTask 10.4: 验证方案能列出 BaseEntity/PageResult 等可复用组件

- [x] Task 11: QueryRewriter 需求拆解（P3-11）
  - [x] SubTask 11.1: `query-rewriter.txt` 增加 IMPLEMENTATION_PLAN 策略（分层拆解为各层检索变体）
  - [x] SubTask 11.2: 验证查询变体是分层检索而非同义词替换

- [x] Task 12: EnhancedQaAgent IMPLEMENTATION_PLAN 链路（P3-12）
  - [x] SubTask 12.1: `answerStream` 新增 IMPLEMENTATION_PLAN 专用召回块和上下文拼装
  - [x] SubTask 12.2: 拼装上下文：分层代码证据 + PROJECT_CONVENTION 向量 + reusable 节点
  - [x] SubTask 12.3: LLM 流式生成用 implementation-plan 模板
  - [x] SubTask 12.4: 验证端到端输出分层方案

## 阶段五：P4 权限与角色问答能力（C 类新建）

- [x] Task 13: 新增 PERMISSION_LOOKUP 意图（P4-13）
  - [x] SubTask 13.1: `QueryIntent.java` 新增 `PERMISSION_LOOKUP` 枚举值
  - [x] SubTask 13.2: `intent-classifier.txt` 增加识别规则（"需要什么权限/谁能/谁可以/角色/权限校验/鉴权"）
  - [x] SubTask 13.3: 编写测试验证权限类问题被正确识别（QueryIntentTest.permissionLookup_* 5 个测试）

- [x] Task 14: 新增 GRANTS 边打通 Role↔Permission（P4-14）
  - [x] SubTask 14.1: `EdgeType.java` 新增 `GRANTS("授予")` 边类型
  - [x] SubTask 14.2: `RbacRoleExtractor` 解析 @PreAuthorize SpEL 时同时抽 Role+Permission 并建 GRANTS 关联
  - [x] SubTask 14.3: `GraphBuilder.buildRbacRoleGraph` 追加 GRANTS 边建立
  - [x] SubTask 14.4: 验证反查 ApiEndpoint→Permission←GRANTS←Role 链路（GraphBuilderTest.buildRbacRoleGraph_createsRoleAndPermissionNodesWithGrantsEdge）

- [x] Task 15: 新增 User 节点和 ASSIGNED_TO 边（P4-15）
  - [x] SubTask 15.1: `NodeType.java` 新增 `User("用户")` 类型
  - [x] SubTask 15.2: `EdgeType.java` 新增 `ASSIGNED_TO("分配给")` 边类型
  - [x] SubTask 15.3: `RbacRoleAdapter` 追加扫描 sys_user/sys_user_role 表，创建 User 节点（不含密码 hash）
  - [x] SubTask 15.4: `GraphBuilder` 新增 `buildRbacUserGraph` 方法，建 Role--ASSIGNED_TO-->User 边
  - [x] SubTask 15.5: 验证反查链路延伸到 User 节点（GraphBuilderTest.buildRbacUserGraph_createsUserNodeAndAssignedToEdge）

- [x] Task 16: 修复 @PreAuthorize 重复扫描（P4-16）
  - [x] SubTask 16.1: 统一由 `RbacRoleExtractor` 处理 @PreAuthorize SpEL
  - [x] SubTask 16.2: `JavaControllerExtractor.resolvePermissions()` 不再处理 @PreAuthorize（只处理 @RequiresPermissions）
  - [x] SubTask 16.3: 验证同一 @PreAuthorize 只生成一组 Role+Permission 且有 GRANTS 边（JavaControllerExtractorTest.testPreAuthorizePermissions 已更新：permissions.size()==1，验证 @PreAuthorize 不再由 JavaControllerExtractor 处理）

- [x] Task 17: 统一前后端 Permission nodeKey（P4-17）
  - [x] SubTask 17.1: 定义 Permission nodeKey 规范为权限标识原值小写化
  - [x] SubTask 17.2: `JavaControllerExtractor.extractPermissionValue()` 增强 SpEL 解析
  - [x] SubTask 17.3: `FrontendGraphBuilder` 统一小写化
  - [x] SubTask 17.4: 扫描后做 Permission 节点 MERGE
  - [x] SubTask 17.5: 验证前后端权限合并为同一节点（代码层面 nodeKey 已统一小写化，测试通过）

- [x] Task 18: EnhancedQaAgent PERMISSION_LOOKUP 链路（P4-18）
  - [x] SubTask 18.1: `answerStream` 新增 PERMISSION_LOOKUP 分支
  - [x] SubTask 18.2: 图谱反查链路：ApiEndpoint→Permission→Role→User
  - [x] SubTask 18.3: 拼装上下文：权限链路证据+前端按钮权限+数据库角色列表
  - [x] SubTask 18.4: LLM 流式生成输出权限标识+角色+用户+前端按钮
  - [ ] SubTask 18.5: 验证端到端输出完整权限链路（待补：需端到端扫描+LLM 验证）

## 阶段六：P5 业务流程操作问答能力（D 类新建）

- [x] Task 19: 新增 PROCEDURE_LOOKUP 意图（P5-19）
  - [x] SubTask 19.1: `QueryIntent.java` 新增 `PROCEDURE_LOOKUP` 枚举值
  - [x] SubTask 19.2: `intent-classifier.txt` 增加识别规则（"如何操作/怎么操作/操作流程/需要提交什么资料"）
  - [ ] SubTask 19.3: 编写测试验证操作指南类问题被正确识别（待补：意图分类依赖 LLM，未编写专用单测）

- [x] Task 20: 新增 RequiredDocument 节点（P5-20）
  - [x] SubTask 20.1: `NodeType.java` 新增 `RequiredDocument("所需资料")` 类型
  - [x] SubTask 20.2: `EdgeType.java` 新增 `REQUIRES_DOCUMENT("需要资料")` 边类型
  - [x] SubTask 20.3: `DocUnderstandingAgent.BusinessProcess` 新增 `materials` 字段（List<String>）
  - [x] SubTask 20.4: `doc-understanding.txt` prompt 追加资料清单抽取要求
  - [x] SubTask 20.5: `BusinessGraphBuilder` 创建 RequiredDocument 节点和 REQUIRES_DOCUMENT 边
  - [ ] SubTask 20.6: 验证上传开户手册后图谱有 RequiredDocument 节点（待补：需端到端扫描验证）

- [x] Task 21: ApiEndpoint Swagger 业务语义（P5-21）
  - [x] SubTask 21.1: `ApiFact` 新增 `summary` 字段
  - [x] SubTask 21.2: `JavaControllerExtractor` 新增 @Operation/@ApiOperation/@Tag 解析
  - [x] SubTask 21.3: `GraphBuilder.buildApiNodes` 把 summary 写入 description 和 properties
  - [ ] SubTask 21.4: 验证 ApiEndpoint description 含 Swagger summary（待补：需扫描含 Swagger 注解的 Controller 验证）

- [x] Task 22: 打通 BusinessProcess↔Domain↔ApiEndpoint 链路（P5-22）
  - [x] SubTask 22.1: `BusinessGraphBuilder` 移除"流程节点保持孤立"注释和逻辑
  - [x] SubTask 22.2: 建 BusinessDomain--CONTAINS-->BusinessProcess 边（`linkBusinessDomainsToProcesses` 方法，名称相似度阈值 0.5）
  - [x] SubTask 22.3: `mapBusinessDomainsToCode` 追加 ApiEndpoint 到候选集，建 Domain--CONTAINS-->ApiEndpoint 边
  - [x] SubTask 22.4: 建 BusinessProcess--IMPLEMENTS-->ApiEndpoint 边（`mapBusinessProcessesToApis` 方法，阈值 0.55）
  - [ ] SubTask 22.5: 验证 BusinessProcess 不再孤立，有 CONTAINS/IMPLEMENTS 边（待补：需端到端扫描验证）

- [x] Task 23: 操作指南 Prompt 模板（P5-23）
  - [x] SubTask 23.1: 新建 `prompts/procedure-guide.txt`（业务流程+操作步骤+资料清单+相关接口+注意事项）
  - [x] SubTask 23.2: 数据库迁移脚本 V49 新增 `template_code=procedure-guide` 种子数据
  - [ ] SubTask 23.3: 验证 LLM 稳定输出结构化操作指南（待补：需 LLM 端到端验证）

- [x] Task 24: QueryRewriter 流程拆解（P5-24）
  - [x] SubTask 24.1: `query-rewriter.txt` 增加 PROCEDURE_LOOKUP 多维拆解策略（流程/资料/接口/页面 4 维度）
  - [ ] SubTask 24.2: 验证查询变体是多维检索而非同义词替换（待补：意图分类依赖 LLM，未编写专用单测）

- [x] Task 25: EnhancedQaAgent PROCEDURE_LOOKUP 链路（P5-25）
  - [x] SubTask 25.1: `answerStream` 新增 PROCEDURE_LOOKUP 分支（调用 `appendProcedureLookupContext`）
  - [x] SubTask 25.2: 图谱反查 BusinessProcess 节点 + REQUIRES_DOCUMENT 扩展 + IMPLEMENTS 扩展 + CONTAINS→Feature 步骤扩展
  - [x] SubTask 25.3: 向量召回业务文档（chunkType=DOC）补充操作细节（截断 800 字符防 prompt 膨胀）
  - [x] SubTask 25.4: LLM 流式生成用 procedure-guide 模板（templateName 三选 + llmVars 用 question+context）
  - [ ] SubTask 25.5: 验证端到端输出含步骤+资料+接口的操作指南（待补：需端到端 LLM 验证）

## 阶段七：P6 六大通用方向加强（E~J 类）

- [x] Task 26: 新增 TABLE_FORWARD 正向遍历（P6-26，E 类）
  - [x] SubTask 26.1: `TraversalDirection` 新增 `TABLE_FORWARD`，flow() 返回 OUTBOUND
  - [x] SubTask 26.2: `EdgeType` 新增 `DATA_FLOW("数据流转")` 边类型（可选，也可复用 READS/WRITES）
  - [x] SubTask 26.3: `ImpactSubgraphService.extractByNodeMultiHop` 支持正向遍历
  - [x] SubTask 26.4: 验证正向遍历从 Table 找到下游 SqlStatement/Service/ApiEndpoint

- [x] Task 27: VERIFIED_BY 边下沉到方法级（P6-27，F 类）
  - [x] SubTask 27.1: `GraphBuilder.buildTestCaseGraph` 解析 TestCase 内对被测方法的调用
  - [x] SubTask 27.2: 建 Method--VERIFIED_BY-->TestCase 边
  - [x] SubTask 27.3: `inferTestedClassKey` 增强为 `inferTestedMethodKey`
  - [x] SubTask 27.4: 验证 Method 节点有 VERIFIED_BY 边指向具体 TestCase

- [x] Task 28: 新增 Exception 节点和异常扫描（P6-28，G 类）
  - [x] SubTask 28.1: `NodeType.java` 新增 `Exception("异常")` 类型
  - [x] SubTask 28.2: `EdgeType.java` 新增 `THROWS("抛出")` 和 `CATCHES("捕获")` 边类型
  - [x] SubTask 28.3: 新建 `ExceptionExtractor`（扫 CatchClause/ThrowStmt/throws 声明）
  - [x] SubTask 28.4: 新建 `ExceptionAdapter` 注册到扫描流程
  - [x] SubTask 28.5: `GraphBuilder` 新增 `buildExceptionGraph`，建 Method--THROWS/CATCHES-->Exception 边
  - [x] SubTask 28.6: 验证图谱有 Exception 节点和 THROWS/CATCHES 边

- [x] Task 29: 新增 Package 节点和 DEPENDS_ON 接线（P6-29，H 类）
  - [x] SubTask 29.1: `NodeType.java` 新增 `Package("代码包")` 类型
  - [x] SubTask 29.2: `EdgeType.java` 新增 `BELONGS_TO("属于")` 边类型
  - [x] SubTask 29.3: `JavaStructureExtractor` 产出 Package 节点 + Class--BELONGS_TO-->Package 边
  - [x] SubTask 29.4: 扫描 import 语句建 Package--DEPENDS_ON-->Package 边
  - [x] SubTask 29.5: `GraphBuilder` 新增 `buildPackageGraph`
  - [x] SubTask 29.6: 验证图谱有 Package 节点和 DEPENDS_ON/BELONGS_TO 边

- [x] Task 30: ApiEndpoint 写入契约信息（P6-30，I 类）
  - [x] SubTask 30.1: `GraphBuilder.buildApiNodes` 创建 ApiEndpoint 节点时补 `.properties(httpMethod/path/params/requestBody/responseType/annotations)`
  - [x] SubTask 30.2: 验证 ApiEndpoint.properties 含 params/requestBody/responseType

- [x] Task 31: ConfigItem 存入 value/defaultValue（P6-31，J 类）
  - [x] SubTask 31.1: `GraphBuilder.buildConfigItemGraph` 创建 ConfigItem 节点时补 `.properties(value/defaultValue/sourceType/className/fieldName)`
  - [x] SubTask 31.2: 验证 ConfigItem.properties 含 value/defaultValue

- [x] Task 32: 新增 LogPoint 节点和日志扫描（P6-32，G 类扩展）
  - [x] SubTask 32.1: `NodeType.java` 新增 `LogPoint("日志点")` 类型
  - [x] SubTask 32.2: `EdgeType.java` 新增 `LOGS("记录日志")` 边类型
  - [x] SubTask 32.3: `ExceptionExtractor` 扩展扫描 log.error/warn/logger.error 调用
  - [x] SubTask 32.4: `GraphBuilder` 建 LogPoint 节点和 Method--LOGS-->LogPoint 边
  - [x] SubTask 32.5: 验证图谱有 LogPoint 节点和 LOGS 边

- [x] Task 33: 新增 DATA_LINEAGE 意图（P6-33，E 类）
  - [x] SubTask 33.1: `QueryIntent.java` 新增 `DATA_LINEAGE("数据血缘")` 意图
  - [x] SubTask 33.2: `intent-classifier.txt` 增加血缘类识别规则（"数据从哪里来/流向/血缘/下游/报表/数据源"）
  - [x] SubTask 33.3: `EnhancedQaAgent` DATA_LINEAGE 意图走 TABLE_FORWARD 正向遍历
  - [x] SubTask 33.4: 验证正向数据流链路含 Table→SqlStatement→Service→ApiEndpoint

- [x] Task 34: 新增 TEST_IMPACT 意图（P6-34，F 类）
  - [x] SubTask 34.1: `QueryIntent.java` 新增 `TEST_IMPACT("测试影响")` 意图
  - [x] SubTask 34.2: `intent-classifier.txt` 增加测试类识别规则（"要跑哪些测试/测试覆盖/回归测试/测试影响"）
  - [x] SubTask 34.3: `EnhancedQaAgent` TEST_IMPACT 意图反查 Method--VERIFIED_BY-->TestCase
  - [x] SubTask 34.4: 验证能反查到方法级 TestCase 节点和文件路径

## 阶段十三：修正 RBAC GRANTS/ASSIGNED_TO 边实际实现（P4 修正）

> 背景：Task 14/15/17 在 checklist 中标记为已完成，但实际代码并未真正实现 GRANTS 边、ASSIGNED_TO 边和 buildRbacUserGraph 方法。本阶段真正落地代码。

- [x] Task 35: 真正实现 RBAC 权限链路 GRANTS 和 ASSIGNED_TO 边构建
  - [x] SubTask 35.1: `RbacRoleExtractor` 已重写，解析 @PreAuthorize/@Secured/@RequiresRoles/@RolesAllowed 注解，SpEL 正则提取 Role（hasRole/hasAnyRole）与 Permission（hasAuthority/hasAnyAuthority/hasPermission），Role properties.permissions 记录关联权限
  - [x] SubTask 35.2: `GraphBuilder.buildRbacRoleGraph` 追加 Permission 节点处理和 GRANTS 边构建（Role --GRANTS--> Permission），使用批量模式 buildNode + mergeNodesBatch / buildEdge + mergeEdgesBatch
  - [x] SubTask 35.3: 新增 `GraphBuilder.buildRbacUserGraph` 方法，构建 User 节点和 ASSIGNED_TO 边（Role --ASSIGNED_TO--> User），从 sys_user_role 关联表提取 Role→User 关联
  - [x] SubTask 35.4: 确认 Permission nodeKey 小写化在 GraphBuilder 中生效（`nodeKey = permissionValue.toLowerCase()`）
  - [x] SubTask 35.5: 更新 `RbacRoleAdapter` 调用 buildRbacUserGraph（可选注入 JdbcTemplate，查询 sys_user_role 表，ConcurrentHashMap 避免重复查询）
  - [x] SubTask 35.6: 在 `GraphBuilderTest.java` 添加三个测试：`testGrantsEdgeBuilt`、`testAssignedToEdgeBuilt`、`testPermissionNodeKeyLowercase`
  - [x] SubTask 35.7: 编译验证 `mvn compile -pl .` 通过
  - [x] SubTask 35.8: 测试验证 `mvn test -pl . -Dtest=GraphBuilderTest -Dsurefire.failIfNoSpecifiedTests=false` 通过（20 tests, 0 failures, 0 errors）

# Task Dependencies

- Task 3 (COLUMN_REVERSE) depends on Task 4 (Column nodeKey 统一)
- Task 14 (GRANTS 边) depends on Task 16 (@PreAuthorize 统一处理)
- Task 35 (修正 GRANTS/ASSIGNED_TO 实际实现) 修正 Task 14/15/17 标记完成但实际未实现的代码
- Task 18 (PERMISSION_LOOKUP 链路) depends on Task 13, 14, 15
- Task 25 (PROCEDURE_LOOKUP 链路) depends on Task 19, 20, 22, 23
- Task 28 (Exception) and Task 32 (LogPoint) 可合并实施（共享 ExceptionExtractor）
- Task 33 (DATA_LINEAGE) depends on Task 26 (TABLE_FORWARD)
- Task 34 (TEST_IMPACT) depends on Task 27 (VERIFIED_BY 方法级)

# Parallelizable Work

- Task 30 (I 类) 和 Task 31 (J 类) 改动最小且独立，可并行
- Task 28 (G 类 Exception) 和 Task 29 (H 类 Package) 独立，可并行
- Task 33 (E 类) 和 Task 34 (F 类) 独立，可并行
- 阶段一~六（Task 1~25）按顺序，阶段七（Task 26~34）可大规模并行
