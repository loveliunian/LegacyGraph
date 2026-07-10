# 实施验证清单

## 阶段八：P7 技术债嗅探（K 类）

- [x] `QueryIntent.java` 已新增 TECH_DEBT 枚举，requiresTechDebt()/requiresPlanner()=false/requiresChangeImpact()=false/getRecommendedGraphDepth()=3
- [x] `intent-classifier.txt` 已增加技术债类识别规则
- [x] 测试：问"系统有哪些循环依赖"被识别为 TECH_DEBT 而非 STRUCTURAL
- [x] `Neo4jGraphDao.detectCircularDependencies` 已实现，用 Cypher shortestPath 检测 DEPENDS_ON/CALLS 环
- [x] `Neo4jQueryRepository` Cypher 查询实现，返回 List<List<GraphNode>>
- [x] 测试：mock 环数据，验证返回环路径列表
- [x] `Neo4jGraphDao.computeFanInOut` 已实现，聚合 CALLS/READS/WRITES 边统计入度/出度
- [x] `JavaStructureExtractor` 扫描时统计 lineCount/methodCount/fieldCount 写入 Class properties
- [x] `GraphBuilder` 写入 fanIn/fanOut 到节点 properties
- [x] 测试：问"哪些类过大该拆"列出 lineCount 超阈值的 Class 节点
- [x] `ArchitectureViolationScanner` 已新建，扫描 Controller→Mapper 跳层 CALLS 边
- [x] `Neo4jGraphDao.findIsolatedNodes` 已实现，查 fanIn=0 且非入口节点
- [x] 违规/死代码结果写入节点 properties（violationType/deadCode）
- [x] 测试：问"Controller 有没有直接调 DAO"列出违规边
- [x] `prompts/tech-debt-report.txt` 已新建（循环依赖/过大类/架构违规/死代码/高耦合/优先级建议结构）
- [x] DB 迁移脚本已新增种子数据（prompt_code=tech-debt-report）
- [x] 测试：问"系统有哪些技术债"输出结构化技术债报告
- [x] `query-rewriter.txt` 已增加 TECH_DEBT 多维拆解策略
- [x] 测试：TECH_DEBT 意图的查询变体是技术债多维检索
- [x] `EnhancedQaAgent.answerStream` 已新增 TECH_DEBT 分支（isDedicatedIntent 互斥保护）
- [x] `appendTechDebtContext` 方法已实现
- [x] 测试：端到端输出结构化技术债报告

## 阶段九：P8 安全审计问答（L 类）

- [x] `QueryIntent.java` 已新增 SECURITY_AUDIT 枚举，requiresSecurityAudit()/互斥方法
- [x] `intent-classifier.txt` 已增加安全审计识别规则
- [x] 测试：问"XX 接口有没有 SQL 注入风险"被识别为 SECURITY_AUDIT
- [x] `NodeType.java` 已新增 SecurityRisk 类型
- [x] `EdgeType.java` 已新增 HAS_RISK 边类型
- [x] `SecurityExtractor` 已新建，扫描 SQL 注入/硬编码密钥/反序列化/XSS/路径遍历
- [x] `SecurityAdapter` 已新建并注册到扫描流程
- [x] `GraphBuilder.buildSecurityGraph` 已实现，建 Method --HAS_RISK--> SecurityRisk 边
- [x] 测试：扫描后图谱有 SecurityRisk 节点和 HAS_RISK 边
- [x] `DatabaseMetadataExtractor` 已按列名规则标记敏感字段（sensitive=true）
- [x] `EdgeType.java` 已新增 MASKED_AT 边类型
- [x] 扫描 @JsonSerialize(DesensitizeSerializer) / DesensitizedUtil 调用，建 Column --MASKED_AT--> Method 边
- [x] 测试：问"身份证号在哪里脱敏"反查到 Column --MASKED_AT--> Method
- [x] `Neo4jGraphDao.findUnprotectedApiEndpoints` 已实现
- [x] 结果写入 ApiEndpoint.properties（unprotected=true）
- [x] 测试：问"哪些接口缺少权限校验"列出 unprotected ApiEndpoint
- [x] `prompts/security-audit-report.txt` 已新建
- [x] DB 迁移脚本已新增种子数据（prompt_code=security-audit-report）
- [x] 测试：问"系统有哪些安全风险"输出结构化安全审计报告
- [x] `query-rewriter.txt` 已增加 SECURITY_AUDIT 多维拆解策略
- [x] 测试：SECURITY_AUDIT 意图的查询变体是安全多维检索
- [x] `EnhancedQaAgent.answerStream` 已新增 SECURITY_AUDIT 分支（isDedicatedIntent 互斥保护）
- [x] `appendSecurityAuditContext` 方法已实现
- [x] 测试：端到端输出结构化安全审计报告

## 阶段十：P9 事务与并发分析（M 类）

- [x] `QueryIntent.java` 已新增 CONCURRENCY 枚举，requiresConcurrencyAnalysis()/互斥方法
- [x] `intent-classifier.txt` 已增加事务并发识别规则
- [x] 测试：问"XX 方法的事务边界是什么"被识别为 CONCURRENCY
- [x] `ConcurrencyExtractor` 已新建，解析 @Transactional propagation/isolation 属性
- [x] 扫描 @Async/synchronized/ReentrantLock/@Lock 注解
- [x] 检测 self-invocation 导致 @Transactional 失效风险
- [x] `ConcurrencyAdapter` 已新建并注册到扫描流程
- [x] 结果写入 Method properties（transactional/propagation/isolation/async/lockType/txFailureRisk）
- [x] 测试：扫描后 Method properties 含 propagation/isolation/async
- [x] `NodeType.java` 已新增 TransactionScope 类型
- [x] `EdgeType.java` 已新增 BOUND_BY 边类型
- [x] `GraphBuilder.buildConcurrencyGraph` 已实现，建 Method --BOUND_BY--> TransactionScope 边
- [x] TransactionScope properties 存 propagation/isolation/ownerMethod/ownerClass
- [x] 测试：问"这个操作跨几个事务"通过 TransactionScope 聚合统计
- [x] `prompts/concurrency-analysis.txt` 已新建
- [x] DB 迁移脚本已新增种子数据（prompt_code=concurrency-analysis）
- [x] 测试：问"XX 方法的事务边界、有没有并发风险"输出结构化事务并发分析
- [x] `EnhancedQaAgent.answerStream` 已新增 CONCURRENCY 分支（isDedicatedIntent 互斥保护）
- [x] `appendConcurrencyContext` 方法已实现
- [x] 测试：端到端输出结构化事务并发分析

## 阶段十一：P10 动态可视化（N 类）

- [x] `QueryIntent.java` 已新增 VISUALIZATION 枚举，requiresVisualization()/互斥方法
- [x] `intent-classifier.txt` 已增加可视化识别规则
- [x] 测试：问"画一下下单流程的时序图"被识别为 VISUALIZATION
- [x] `service/viz/DiagramGenerator.java` 已新建，支持 5 种图类型生成
- [x] `generateSequenceDiagram` 从 CALLS 边生成 mermaid sequenceDiagram
- [x] `generateDependencyGraph` 从 DEPENDS_ON 边生成 mermaid graph LR
- [x] `generateCallChain` 从 CALLS 边生成 mermaid graph TD
- [x] `generateDataFlowDiagram` 从 READS/WRITES/DATA_FLOW 边生成 mermaid graph LR
- [x] `generateBusinessFlowDiagram` 从 CONTAINS/IMPLEMENTS/REQUIRES_DOCUMENT 边生成 mermaid graph LR
- [x] 测试：问"画一下 orderService.create 的调用链"生成 mermaid 调用链图
- [x] `prompts/visualization.txt` 已新建（指示 LLM 生成 mermaid 代码块 + 文字说明）
- [x] DB 迁移脚本已新增种子数据（prompt_code=visualization）
- [x] 测试：问"画一下 XX 流程的时序图"输出 mermaid 时序图代码块
- [x] `query-rewriter.txt` 已增加 VISUALIZATION 按关键词细分策略
- [x] 测试：问"画时序图"和"画依赖图"触发不同图类型
- [x] `EnhancedQaAgent.answerStream` 已新增 VISUALIZATION 分支（isDedicatedIntent 互斥保护）
- [x] `appendVisualizationContext` 方法已实现
- [x] 测试：端到端输出 mermaid 时序图代码块

## 阶段十二：P11 精准测试生成（O 类）

- [x] `QueryIntent.java` 已新增 TEST_GENERATION 枚举，requiresTestGeneration()/互斥方法
- [x] `intent-classifier.txt` 已增加测试生成识别规则
- [x] 测试：问"帮 XX 方法生成单元测试"被识别为 TEST_GENERATION
- [x] `Neo4jGraphDao.findUncoveredMethods` 已实现，查无 VERIFIED_BY 边的 Method 节点
- [x] 按 fanIn 降序排序
- [x] 输出覆盖率统计：covered / total Method 节点数
- [x] 测试：问"哪些方法没有测试覆盖"列出未覆盖 Method 节点
- [x] `agent/TestGenerationAgent.java` 已新建
- [x] 从 Method properties 获取方法签名/参数类型，从 CALLS 边获取依赖（mock 对象）
- [x] 从 ApiEndpoint.properties 获取契约信息（契约测试场景）
- [x] 输出测试代码模板：@ExtendWith + @Mock + @InjectMocks + 测试方法
- [x] 测试：问"帮 orderService.create 方法生成单元测试"输出完整 JUnit 测试类代码
- [x] `prompts/test-generation.txt` 已新建
- [x] DB 迁移脚本已新增种子数据（prompt_code=test-generation）
- [x] 测试：问"帮 XX 方法生成单元测试"输出完整测试类代码
- [x] `EnhancedQaAgent.answerStream` 已新增 TEST_GENERATION 分支（isDedicatedIntent 互斥保护）
- [x] `appendTestGenerationContext` 方法已实现
- [x] 测试：端到端输出完整测试类代码，含多个测试场景

## 全局验证

- [x] `mvn compile` 编译通过
- [x] `mvn test` 全部测试通过（无 failures, 无 errors）
- [x] QueryIntent 枚举从 12 种扩展到 17 种，5 个新意图的 requiresXxx() 方法互斥正确
- [x] EnhancedQaAgent 5 个新意图分支通过 isDedicatedIntent 互斥保护，不与已有分支冲突
- [x] detectListingNodeType 补齐 SecurityRisk/TransactionScope 类型词识别
- [x] 5 个新 prompt 模板的 DB 种子数据已新增
- [x] 新建文件有合理的类注释和方法注释
- [x] 配置项（如阈值、复杂度阈值）可配置，未硬编码
