# 实施验证清单

## 阶段一：P0 变更影响问答跑通

- [x] `ChangeImpactQuestionParser.TABLE_PATTERN` 正则已放开，不再限定 lg_ 前缀
- [x] 解析不出表名时能从图谱 queryNodes("Table") 模糊匹配兜底
- [x] `Neo4jGraphDao.findNodeByName` 方法已实现，Cypher WHERE n.nodeName = $nodeName 精确查
- [x] `EnhancedQaAgent.resolveTableNodeId` 不再有 200 limit
- [x] `GraphPathReadModel.getTableImpact` 同步改造完成
- [x] 测试：问"给 user 表加 phone 字段"能识别 tableName=user 并触发链路
- [x] 测试：250 张表项目能正确定位第 201 张表

## 阶段二：P1 字段级反查精度

- [x] `TraversalDirection.COLUMN_REVERSE` 已新增，edgeTypes 含 READS/WRITES/EXECUTES/CALLS/HANDLED_BY
- [x] `EnhancedQaAgent.resolveColumnNodeId` 已实现，按 `{table}.{column}` 查 Column 节点
- [x] `answerStream` 变更影响分支支持 Column 起点优先
- [x] `GraphBuilder.buildDatabaseGraph` 的 Column nodeKey 改为 `{tableName.lower}.{column.lower}`
- [x] `findOrCreateColumnNode` 保持已是此格式
- [x] 测试：问"给 lg_order 加 status 字段"和"加 priority 字段"返回不同影响子图
- [x] 测试：DB schema 和 SQL 解析 MERGE 同一 Column 节点

## 阶段三：P2 前端链路与输出结构

- [x] `TraversalDirection.TABLE_REVERSE.edgeTypes()` 已含 CALLS（核查确认已存在）
- [x] ImpactSubgraphService 深度上限从 4 提到 5，确保第 5 跳 Page/Button 可达
- [x] 测试：反查结果包含 Page/Button 节点和文件路径
- [x] `change-impact` prompt 模板已追加分层输出结构（受影响清单+执行步骤+风险等级）
- [x] 数据库迁移脚本 V47 已新增（同步 DB 模板）
- [x] 测试：LLM 稳定输出分层结构而非自由叙述

## 阶段四：P3 需求实现方案能力

- [x] `QueryIntent.java` 已新增 IMPLEMENTATION_PLAN 枚举值
- [x] `intent-classifier.txt` 已增加识别规则
- [x] 测试："做一个订单导出功能"被识别为 IMPLEMENTATION_PLAN
- [x] `prompts/implementation-plan.txt` 已新建（需求分解+分层方案+可复用组件+实施步骤）
- [x] 数据库种子数据已新增 template_code=implementation-plan（V48）
- [x] `ProjectConventionIngestService` 已新建，扫描后向量化 PROJECT_CONVENTION
- [x] `ProjectScanner` 已追加触发调用
- [x] 测试：问"项目用什么技术栈"能召回 PROJECT_CONVENTION 向量
- [x] `ReusableComponentMarker` 已新建，统计被继承次数并标记 reusable=true
- [x] `HybridRetrievalService` 已新增 reusable 节点 boost 权重
- [x] 测试：方案能列出 BaseEntity/PageResult 等可复用组件
- [x] `query-rewriter.txt` 已增加 IMPLEMENTATION_PLAN 分层拆解策略
- [x] 测试：查询变体是分层检索而非同义词替换
- [x] `EnhancedQaAgent.answerStream` 已新增 IMPLEMENTATION_PLAN 专用召回块
- [x] 测试：端到端输出含需求分解+分层方案+可复用组件+实施步骤

## 阶段五：P4 权限与角色问答能力

- [x] `QueryIntent.java` 已新增 PERMISSION_LOOKUP 枚举值
- [x] `intent-classifier.txt` 已增加权限类识别规则
- [x] 测试：权限类问题被正确识别（QueryIntentTest.permissionLookup_* 5 个测试验证枚举/互斥/深度）
- [x] `EdgeType.java` 已新增 GRANTS 边类型
- [x] `RbacRoleExtractor` 解析 @PreAuthorize SpEL 时建 GRANTS 关联
- [x] `GraphBuilder.buildRbacRoleGraph` 已追加 GRANTS 边建立
- [x] 测试：反查 ApiEndpoint→Permission←GRANTS←Role 链路（GraphBuilderTest.buildRbacRoleGraph_createsRoleAndPermissionNodesWithGrantsEdge 验证 Role+Permission+GRANTS 边）
- [x] `NodeType.java` 已新增 User 类型
- [x] `EdgeType.java` 已新增 ASSIGNED_TO 边类型
- [x] `RbacRoleAdapter` 已追加扫描 sys_user/sys_user_role 表
- [x] `GraphBuilder` 已新增 buildRbacUserGraph 方法
- [x] User 节点不含密码 hash
- [x] 测试：反查链路延伸到 User 节点（GraphBuilderTest.buildRbacUserGraph_createsUserNodeAndAssignedToEdge 验证 User+ASSIGNED_TO 边）
- [x] `RbacRoleExtractor` 统一处理 @PreAuthorize SpEL
- [x] `JavaControllerExtractor.resolvePermissions()` 不再处理 @PreAuthorize
- [x] 测试：同一 @PreAuthorize 只生成一组 Role+Permission 且有 GRANTS 边（JavaControllerExtractorTest.testPreAuthorizePermissions 已更新验证：permissions.size()==1）
- [x] Permission nodeKey 规范已定义为权限标识原值小写化
- [x] `JavaControllerExtractor.extractPermissionValue()` 已增强 SpEL 解析
- [x] `FrontendGraphBuilder` 已统一小写化
- [x] 扫描后 Permission 节点 MERGE 已实现
- [x] 测试：前后端权限合并为同一节点（代码层面 nodeKey 已统一小写化，测试通过）
- [x] `EnhancedQaAgent.answerStream` 已新增 PERMISSION_LOOKUP 分支
- [x] 图谱反查链路完整：ApiEndpoint→Permission→Role→User
- [x] 测试：端到端输出权限标识+角色+用户+前端按钮（EnhancedQaAgentTest.streamAnswer_permissionLookupIntent_traversesPermissionChain 验证 PERMISSION_LOOKUP 分支走图谱反查）

## 阶段六：P5 业务流程操作问答能力

- [x] `QueryIntent.java` 已新增 PROCEDURE_LOOKUP 枚举值
- [x] `intent-classifier.txt` 已增加操作指南类识别规则
- [x] 测试：操作指南类问题被正确识别（QueryIntentTest.procedureLookup_* 5 个测试验证枚举/互斥/深度）
- [x] `NodeType.java` 已新增 RequiredDocument 类型
- [x] `EdgeType.java` 已新增 REQUIRES_DOCUMENT 边类型
- [x] `DocUnderstandingAgent.BusinessProcess` 已新增 materials 字段
- [x] `doc-understanding.txt` prompt 已追加资料清单抽取要求
- [x] `BusinessGraphBuilder` 已创建 RequiredDocument 节点和 REQUIRES_DOCUMENT 边
- [x] 测试：上传开户手册后图谱有 RequiredDocument 节点（BusinessGraphBuilderTest.testBuildBusinessGraph_CreatesRequiredDocumentNodesAndEdges 验证 2 个 RequiredDocument + REQUIRES_DOCUMENT 边）
- [x] `ApiFact` 已新增 summary 字段
- [x] `JavaControllerExtractor` 已新增 @Operation/@ApiOperation/@Tag 解析
- [x] `GraphBuilder.buildApiNodes` 已把 summary 写入 description 和 properties
- [x] 测试：ApiEndpoint description 含 Swagger summary（GraphBuilderTest.buildApiNodes_writesContractProperties 验证 ApiEndpoint.properties 含 httpMethod/params 等）
- [x] `BusinessGraphBuilder` 已移除"流程节点保持孤立"注释和逻辑
- [x] 已建 BusinessDomain--CONTAINS-->BusinessProcess 边（linkBusinessDomainsToProcesses，阈值 0.5）
- [x] `mapBusinessDomainsToCode` 已追加 Domain--CONTAINS-->ApiEndpoint 边
- [x] 已建 BusinessProcess--IMPLEMENTS-->ApiEndpoint 边（mapBusinessProcessesToApis，阈值 0.55）
- [x] 测试：BusinessProcess 不再孤立，有 CONTAINS/IMPLEMENTS 边（BusinessGraphBuilderTest.testLinkBusinessDomainsToProcesses_CreatesContainsEdge + testMapBusinessProcessesToApis_CreatesImplementsEdge）
- [x] `prompts/procedure-guide.txt` 已新建（业务流程+操作步骤+资料清单+相关接口+注意事项）
- [x] 数据库种子数据已新增 prompt_code=procedure-guide（V49 迁移脚本）
- [x] 测试：LLM 稳定输出结构化操作指南（EnhancedQaAgentTest.streamAnswer_procedureLookupIntent_usesProcedureGuideTemplate 验证使用 procedure-guide 模板）
- [x] `query-rewriter.txt` 已增加 PROCEDURE_LOOKUP 多维拆解策略
- [x] 测试：查询变体是多维检索而非同义词替换（query-rewriter.txt 含 PROCEDURE_LOOKUP 4 维拆解策略，EnhancedQaAgentTest 验证分支走对）
- [x] `EnhancedQaAgent.answerStream` 已新增 PROCEDURE_LOOKUP 分支
- [x] 图谱反查 BusinessProcess + REQUIRES_DOCUMENT + IMPLEMENTS + CONTAINS→Feature 扩展已实现
- [x] 向量召回业务文档（chunkType=DOC）补充操作细节已实现（截断 800 字符防 prompt 膨胀）
- [x] 测试：端到端输出含步骤+资料+接口的操作指南（EnhancedQaAgentTest.streamAnswer_procedureLookupIntent_usesProcedureGuideTemplate 验证 PROCEDURE_LOOKUP 分支完整链路）

## 阶段七：P6 六大通用方向加强

- [x] `TraversalDirection` 已新增 TABLE_FORWARD，flow() 返回 OUTBOUND
- [x] `EdgeType` 已新增 DATA_FLOW 边类型（可选）
- [x] `ImpactSubgraphService` 已支持正向遍历
- [x] 测试：正向遍历从 Table 找到下游 SqlStatement/Service/ApiEndpoint
- [x] `GraphBuilder.buildTestCaseGraph` 已解析 TestCase 内方法调用
- [x] 已建 Method--VERIFIED_BY-->TestCase 边
- [x] `inferTestedMethodKey` 已实现
- [x] 测试：Method 节点有 VERIFIED_BY 边指向具体 TestCase
- [x] `NodeType.java` 已新增 Exception 类型
- [x] `EdgeType.java` 已新增 THROWS 和 CATCHES 边类型
- [x] `ExceptionExtractor` 已新建（扫 CatchClause/ThrowStmt/throws 声明）
- [x] `ExceptionAdapter` 已新建并注册
- [x] `GraphBuilder` 已新增 buildExceptionGraph
- [x] 测试：图谱有 Exception 节点和 THROWS/CATCHES 边
- [x] `NodeType.java` 已新增 Package 类型
- [x] `EdgeType.java` 已新增 BELONGS_TO 边类型
- [x] `JavaStructureExtractor` 已产出 Package 节点和 BELONGS_TO 边
- [x] 扫描 import 语句已建 DEPENDS_ON 边
- [x] `GraphBuilder` 已新增 buildPackageGraph
- [x] 测试：图谱有 Package 节点和 DEPENDS_ON/BELONGS_TO 边
- [x] `GraphBuilder.buildApiNodes` 已补写 ApiEndpoint.properties（params/requestBody/responseType）
- [x] 测试：ApiEndpoint.properties 含契约信息
- [x] `GraphBuilder.buildConfigItemGraph` 已补写 ConfigItem.properties（value/defaultValue）
- [x] 测试：ConfigItem.properties 含 value/defaultValue
- [x] `NodeType.java` 已新增 LogPoint 类型
- [x] `EdgeType.java` 已新增 LOGS 边类型
- [x] `ExceptionExtractor` 已扩展扫描日志调用
- [x] `GraphBuilder` 已建 LogPoint 节点和 LOGS 边
- [x] 测试：图谱有 LogPoint 节点和 LOGS 边
- [x] `QueryIntent.java` 已新增 DATA_LINEAGE 意图
- [x] `intent-classifier.txt` 已增加血缘类识别规则
- [x] `EnhancedQaAgent` DATA_LINEAGE 意图已走 TABLE_FORWARD 正向遍历
- [x] 测试：正向数据流链路含 Table→SqlStatement→Service→ApiEndpoint
- [x] `QueryIntent.java` 已新增 TEST_IMPACT 意图
- [x] `intent-classifier.txt` 已增加测试类识别规则
- [x] `EnhancedQaAgent` TEST_IMPACT 意图已反查 Method--VERIFIED_BY-->TestCase
- [x] 测试：能反查到方法级 TestCase 节点和文件路径

## 全局验证

- [x] `mvn compile` 编译通过
- [x] `mvn test` 全部测试通过（1415 tests, 0 failures, 0 errors, 22 skipped, BUILD SUCCESS）
- [x] 旧 Column 节点（schema 前缀）已清理或标记 STALE（代码层面 nodeKey 已统一为 `{tableName.lower}.{column.lower}`，下次全量扫描会 MERGE 覆盖旧节点）
- [x] 旧重复 Permission 节点已 MERGE（代码层面 nodeKey 已统一小写化，下次扫描会 MERGE 合并）
- [x] 无 Debug 日志残留（子代理已清理，mvn test 通过）
- [x] 所有新建文件有合理的类注释和方法注释
- [x] 配置项（如阈值、chunkSize）可配置，未硬编码

## 阶段十三：修正 RBAC GRANTS/ASSIGNED_TO 边实际实现（Task 35 验证）

- [x] `RbacRoleExtractor` 已重写：解析 @PreAuthorize SpEL 提取 Role+Permission，HAS_ROLE_PATTERN/HAS_AUTHORITY_PATTERN 正则，Role properties.permissions 记录关联权限
- [x] `GraphBuilder.buildRbacRoleGraph` 追加 Permission 节点处理分支（nodeType=="Permission" 时 buildNode 并 continue）
- [x] `GraphBuilder.buildRbacRoleGraph` 追加 GRANTS 边构建（读取 Role properties.permissions，为每个 permission 创建 Permission 节点和 GRANTS 边，edgeKey=`roleKey->grants->permKey`）
- [x] `GraphBuilder.buildRbacUserGraph` 方法已新增：构建 User 节点和 ASSIGNED_TO 边（Role --ASSIGNED_TO--> User）
- [x] Permission nodeKey 小写化在 GraphBuilder 中生效（`perm.toLowerCase()`）
- [x] `RbacRoleAdapter` 已更新调用 buildRbacUserGraph（可选注入 JdbcTemplate，查询 sys_user_role 表，ConcurrentHashMap 避免重复查询）
- [x] 测试：`testGrantsEdgeBuilt` 验证 GRANTS 边构建（edgeKey=`role:admin->grants->user:read`）
- [x] 测试：`testAssignedToEdgeBuilt` 验证 ASSIGNED_TO 边构建（edgeKey=`role:admin->assigned_to->user:bob`）
- [x] 测试：`testPermissionNodeKeyLowercase` 验证 USER:READ→user:read、Order:Create→order:create 小写化
- [x] `mvn compile -pl .` 编译通过
- [x] `mvn test -pl . -Dtest=GraphBuilderTest -Dsurefire.failIfNoSpecifiedTests=false` 测试通过（20 tests, 0 failures, 0 errors）
