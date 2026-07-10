# Tasks

## 阶段八：P7 技术债嗅探（K 类）

- [x] Task 35: 新增 TECH_DEBT 意图枚举与意图分类规则
  - [x] SubTask 35.1: QueryIntent.java 新增 TECH_DEBT 枚举，实现 requiresTechDebt()、requiresPlanner()=false、requiresChangeImpact()=false、getRecommendedGraphDepth()=3
  - [x] SubTask 35.2: intent-classifier.txt 增加技术债类识别规则（循环依赖/过大/过载/该拆/架构违规/死代码/未调用/扇入扇出/耦合/技术债/坏味道）
  - [x] SubTask 35.3: 测试：问"系统有哪些循环依赖"被识别为 TECH_DEBT 而非 STRUCTURAL

- [x] Task 36: 循环依赖检测图算法
  - [x] SubTask 36.1: Neo4jGraphDao 新增 detectCircularDependencies(projectId, versionId, nodeType, depth)，用 Cypher shortestPath 检测 DEPENDS_ON 和 CALLS 边的环
  - [x] SubTask 36.2: Neo4jQueryRepository 实现 Cypher 查询，返回 List<List<GraphNode>>
  - [x] SubTask 36.3: 测试：mock 环数据，验证返回环路径列表

- [x] Task 37: 扇入扇出统计与过大类识别
  - [x] SubTask 37.1: Neo4jGraphDao 新增 computeFanInOut(projectId, versionId, nodeType)，聚合 CALLS/READS/WRITES 边统计入度/出度
  - [x] SubTask 37.2: JavaStructureExtractor 扫描时统计 lineCount/methodCount/fieldCount 写入 Class 节点 properties
  - [x] SubTask 37.3: GraphBuilder.buildPackageGraph/buildClassNodes 写入 fanIn/fanOut 到 properties
  - [x] SubTask 37.4: 测试：问"哪些类过大该拆"列出 lineCount 超阈值的 Class 节点

- [x] Task 38: 架构违规扫描与死代码识别
  - [x] SubTask 38.1: 新建 ArchitectureViolationScanner，扫描 Controller→Mapper 跳层 CALLS 边
  - [x] SubTask 38.2: Neo4jGraphDao 新增 findIsolatedNodes(projectId, versionId, nodeType)，查 fanIn=0 且非 ApiEndpoint/Page 的节点
  - [x] SubTask 38.3: 违规/死代码结果写入对应节点 properties（violationType/deadCode）
  - [x] SubTask 38.4: 测试：问"Controller 有没有直接调 DAO"列出违规边

- [x] Task 39: 技术债 Prompt 模板 tech-debt-report
  - [x] SubTask 39.1: 新建 prompts/tech-debt-report.txt（循环依赖/过大类/架构违规/死代码/高耦合/优先级建议结构）
  - [x] SubTask 39.2: 新增 DB 迁移脚本种子数据（prompt_code=tech-debt-report）
  - [x] SubTask 39.3: 测试：问"系统有哪些技术债"输出结构化技术债报告

- [x] Task 40: QueryRewriter 技术债多维拆解策略
  - [x] SubTask 40.1: query-rewriter.txt 增加 TECH_DEBT 多维拆解（循环依赖/过大类/违规/死代码）
  - [x] SubTask 40.2: 测试：TECH_DEBT 意图的查询变体是技术债多维检索

- [x] Task 41: EnhancedQaAgent TECH_DEBT 处理链路
  - [x] SubTask 41.1: answerStream 新增 TECH_DEBT 分支（isDedicatedIntent 互斥保护）
  - [x] SubTask 41.2: 新增 appendTechDebtContext 方法：调用 detectCircularDependencies + fanIn/fanOut 超阈值节点 + 违规边 + 死代码
  - [x] SubTask 41.3: 测试：端到端输出结构化技术债报告

## 阶段九：P8 安全审计问答（L 类）

- [x] Task 42: 新增 SECURITY_AUDIT 意图枚举与意图分类规则
  - [x] SubTask 42.1: QueryIntent.java 新增 SECURITY_AUDIT 枚举，实现 requiresSecurityAudit()、互斥方法
  - [x] SubTask 42.2: intent-classifier.txt 增加安全审计识别规则（SQL注入/安全漏洞/密钥/密码/Token/硬编码/脱敏/敏感数据/反序列化/权限校验/安全风险）
  - [x] SubTask 42.3: 测试：问"XX 接口有没有 SQL 注入风险"被识别为 SECURITY_AUDIT

- [x] Task 43: 新增 SecurityExtractor 安全扫描器
  - [x] SubTask 43.1: NodeType.java 新增 SecurityRisk 类型
  - [x] SubTask 43.2: EdgeType.java 新增 HAS_RISK 边类型
  - [x] SubTask 43.3: 新建 SecurityExtractor，扫描 SQL 注入（${} 拼接/Statement 拼接）、硬编码密钥（password/secret/apiKey 赋值）、不安全反序列化（ObjectInputStream/XMLDecoder）、XSS、路径遍历
  - [x] SubTask 43.4: 新建 SecurityAdapter 注册到扫描流程
  - [x] SubTask 43.5: GraphBuilder 新增 buildSecurityGraph，建 Method --HAS_RISK--> SecurityRisk 边
  - [x] SubTask 43.6: 测试：扫描后图谱有 SecurityRisk 节点和 HAS_RISK 边

- [x] Task 44: 敏感数据标记与脱敏追踪
  - [x] SubTask 44.1: DatabaseMetadataExtractor 按列名规则标记敏感字段（id_card/passport/phone/mobile/bank_card/email/address）→ properties.sensitive=true
  - [x] SubTask 44.2: EdgeType.java 新增 MASKED_AT 边类型
  - [x] SubTask 44.3: 扫描 @JsonSerialize(using=DesensitizeSerializer) / DesensitizedUtil 调用，建 Column --MASKED_AT--> Method 边
  - [x] SubTask 44.4: 测试：问"身份证号在哪里脱敏"反查到 Column --MASKED_AT--> Method

- [x] Task 45: 权限校验缺失检测
  - [x] SubTask 45.1: Neo4jGraphDao 新增 findUnprotectedApiEndpoints(projectId, versionId)，查无 REQUIRES_PERMISSION 边的 ApiEndpoint
  - [x] SubTask 45.2: 结果写入 ApiEndpoint.properties（unprotected=true）
  - [x] SubTask 45.3: 测试：问"哪些接口缺少权限校验"列出 unprotected ApiEndpoint

- [x] Task 46: 安全审计 Prompt 模板 security-audit-report
  - [x] SubTask 46.1: 新建 prompts/security-audit-report.txt（SQL注入/硬编码密钥/敏感数据/权限缺失/反序列化/修复建议结构）
  - [x] SubTask 46.2: 新增 DB 迁移脚本种子数据（prompt_code=security-audit-report）
  - [x] SubTask 46.3: 测试：问"系统有哪些安全风险"输出结构化安全审计报告

- [x] Task 47: QueryRewriter 安全审计多维拆解策略
  - [x] SubTask 47.1: query-rewriter.txt 增加 SECURITY_AUDIT 多维拆解（注入/密钥/脱敏/权限）
  - [x] SubTask 47.2: 测试：SECURITY_AUDIT 意图的查询变体是安全多维检索

- [x] Task 48: EnhancedQaAgent SECURITY_AUDIT 处理链路
  - [x] SubTask 48.1: answerStream 新增 SECURITY_AUDIT 分支（isDedicatedIntent 互斥保护）
  - [x] SubTask 48.2: 新增 appendSecurityAuditContext 方法：反查 SecurityRisk + HAS_RISK + sensitive Column + MASKED_AT + unprotected ApiEndpoint
  - [x] SubTask 48.3: 测试：端到端输出结构化安全审计报告

## 阶段十：P9 事务与并发分析（M 类）

- [x] Task 49: 新增 CONCURRENCY 意图枚举与意图分类规则
  - [x] SubTask 49.1: QueryIntent.java 新增 CONCURRENCY 枚举，实现 requiresConcurrencyAnalysis()、互斥方法
  - [x] SubTask 49.2: intent-classifier.txt 增加事务并发识别规则（事务/传播/隔离级别/并发/线程安全/竞态/锁/@Async/@Transactional/self-invocation）
  - [x] SubTask 49.3: 测试：问"XX 方法的事务边界是什么"被识别为 CONCURRENCY

- [x] Task 50: 新增 ConcurrencyExtractor 事务/并发扫描器
  - [x] SubTask 50.1: 新建 ConcurrencyExtractor，解析 @Transactional 的 propagation 和 isolation 属性
  - [x] SubTask 50.2: 扫描 @Async、synchronized、ReentrantLock、@Lock 注解
  - [x] SubTask 50.3: 检测 self-invocation：同类内方法 A 调用方法 B（B 有 @Transactional），标记 txFailureRisk=true
  - [x] SubTask 50.4: 新建 ConcurrencyAdapter 注册到扫描流程
  - [x] SubTask 50.5: 结果写入 Method 节点 properties（transactional/propagation/isolation/async/lockType/txFailureRisk）
  - [x] SubTask 50.6: 测试：扫描后 Method 节点 properties 含 propagation/isolation/async

- [x] Task 51: 事务边界图谱建模
  - [x] SubTask 51.1: NodeType.java 新增 TransactionScope 类型
  - [x] SubTask 51.2: EdgeType.java 新增 BOUND_BY 边类型
  - [x] SubTask 51.3: GraphBuilder 新增 buildConcurrencyGraph，建 Method --BOUND_BY--> TransactionScope 边
  - [x] SubTask 51.4: TransactionScope 节点 properties 存 propagation/isolation/ownerMethod/ownerClass
  - [x] SubTask 51.5: 测试：问"这个操作跨几个事务"通过 TransactionScope 聚合统计

- [x] Task 52: 事务并发 Prompt 模板 concurrency-analysis
  - [x] SubTask 52.1: 新建 prompts/concurrency-analysis.txt（事务边界/传播链路/潜在风险/并发安全/修复建议结构）
  - [x] SubTask 52.2: 新增 DB 迁移脚本种子数据（prompt_code=concurrency-analysis）
  - [x] SubTask 52.3: 测试：问"XX 方法的事务边界、有没有并发风险"输出结构化事务并发分析

- [x] Task 53: EnhancedQaAgent CONCURRENCY 处理链路
  - [x] SubTask 53.1: answerStream 新增 CONCURRENCY 分支（isDedicatedIntent 互斥保护）
  - [x] SubTask 53.2: 新增 appendConcurrencyContext 方法：反查 Method 事务属性 + BOUND_BY + 同 TransactionScope 方法 + self-invocation + @Async/锁
  - [x] SubTask 53.3: 测试：端到端输出结构化事务并发分析

## 阶段十一：P10 动态可视化（N 类）

- [x] Task 54: 新增 VISUALIZATION 意图枚举与意图分类规则
  - [x] SubTask 54.1: QueryIntent.java 新增 VISUALIZATION 枚举，实现 requiresVisualization()、互斥方法
  - [x] SubTask 54.2: intent-classifier.txt 增加可视化识别规则（画/画图/时序图/流程图/依赖图/调用链图/数据流图/架构图/mermaid/可视化）
  - [x] SubTask 54.3: 测试：问"画一下下单流程的时序图"被识别为 VISUALIZATION

- [x] Task 55: 调用链→时序图转换器 DiagramGenerator
  - [x] SubTask 55.1: 新建 service/viz/DiagramGenerator.java，支持 5 种图类型生成
  - [x] SubTask 55.2: generateSequenceDiagram：从入口方法沿 CALLS 边遍历，生成 mermaid sequenceDiagram（参与者=类，消息=方法调用）
  - [x] SubTask 55.3: generateDependencyGraph：沿 DEPENDS_ON 边生成 mermaid graph LR
  - [x] SubTask 55.4: generateCallChain：沿 CALLS 边正向/反向生成 mermaid graph TD
  - [x] SubTask 55.5: generateDataFlowDiagram：沿 READS/WRITES/DATA_FLOW 边生成 mermaid graph LR
  - [x] SubTask 55.6: generateBusinessFlowDiagram：沿 CONTAINS/IMPLEMENTS/REQUIRES_DOCUMENT 边生成 mermaid graph LR
  - [x] SubTask 55.7: 测试：问"画一下 orderService.create 的调用链"生成 mermaid 调用链图

- [x] Task 56: 可视化 Prompt 模板 visualization
  - [x] SubTask 56.1: 新建 prompts/visualization.txt（指示 LLM 生成 mermaid 代码块 + 文字说明）
  - [x] SubTask 56.2: 新增 DB 迁移脚本种子数据（prompt_code=visualization）
  - [x] SubTask 56.3: 测试：问"画一下 XX 流程的时序图"输出 mermaid 时序图代码块

- [x] Task 57: QueryRewriter 可视化拆图策略
  - [x] SubTask 57.1: query-rewriter.txt 增加 VISUALIZATION 按关键词细分（时序图→CALLS/依赖图→DEPENDS_ON/调用链→CALLS/数据流→READS+WRITES/业务流程→CONTAINS+IMPLEMENTS）
  - [x] SubTask 57.2: 测试：问"画时序图"和"画依赖图"触发不同图类型

- [x] Task 58: EnhancedQaAgent VISUALIZATION 处理链路
  - [x] SubTask 58.1: answerStream 新增 VISUALIZATION 分支（isDedicatedIntent 互斥保护）
  - [x] SubTask 58.2: 新增 appendVisualizationContext 方法：识别图类型 → 调用 DiagramGenerator → 拼装 mermaid 代码 + 节点说明
  - [x] SubTask 58.3: 测试：端到端输出 mermaid 时序图代码块

## 阶段十二：P11 精准测试生成（O 类）

- [x] Task 59: 新增 TEST_GENERATION 意图枚举与意图分类规则
  - [x] SubTask 59.1: QueryIntent.java 新增 TEST_GENERATION 枚举，实现 requiresTestGeneration()、互斥方法
  - [x] SubTask 59.2: intent-classifier.txt 增加测试生成识别规则（生成测试/写测试/补测试/测试用例/单元测试/契约测试/回归测试）
  - [x] SubTask 59.3: 测试：问"帮 XX 方法生成单元测试"被识别为 TEST_GENERATION

- [x] Task 60: 覆盖率缺口分析
  - [x] SubTask 60.1: Neo4jGraphDao 新增 findUncoveredMethods(projectId, versionId)，查无 VERIFIED_BY 边的 Method 节点
  - [x] SubTask 60.2: 按 fanIn 降序排序（被调用多的优先补测）
  - [x] SubTask 60.3: 输出覆盖率统计：covered / total Method 节点数
  - [x] SubTask 60.4: 测试：问"哪些方法没有测试覆盖"列出未覆盖 Method 节点

- [x] Task 61: 新增 TestGenerationAgent 测试代码生成
  - [x] SubTask 61.1: 新建 agent/TestGenerationAgent.java，输入目标 Method 节点 + 调用链上下文，输出 JUnit 测试代码
  - [x] SubTask 61.2: 从 Method properties 获取方法签名/参数类型，从 CALLS 边获取依赖（mock 对象）
  - [x] SubTask 61.3: 从 ApiEndpoint.properties 获取契约信息（契约测试场景）
  - [x] SubTask 61.4: 输出测试代码模板：@ExtendWith(MockitoExtension.class) + @Mock + @InjectMocks + 测试方法
  - [x] SubTask 61.5: 测试：问"帮 orderService.create 方法生成单元测试"输出完整 JUnit 测试类代码

- [x] Task 62: 测试生成 Prompt 模板 test-generation
  - [x] SubTask 62.1: 新建 prompts/test-generation.txt（测试目标/测试场景/测试代码/覆盖建议结构）
  - [x] SubTask 62.2: 新增 DB 迁移脚本种子数据（prompt_code=test-generation）
  - [x] SubTask 62.3: 测试：问"帮 XX 方法生成单元测试"输出完整测试类代码

- [x] Task 63: EnhancedQaAgent TEST_GENERATION 处理链路
  - [x] SubTask 63.1: answerStream 新增 TEST_GENERATION 分支（isDedicatedIntent 互斥保护）
  - [x] SubTask 63.2: 新增 appendTestGenerationContext 方法：解析方法名 → 图谱定位 Method → 反查 CALLS 依赖 → 反查 VERIFIED_BY 现有测试 → 调用 TestGenerationAgent
  - [x] SubTask 63.3: 测试：端到端输出完整测试类代码，含多个测试场景

## Task Dependencies
- Task 36 依赖 Task 35（TECH_DEBT 意图）
- Task 37 依赖 Task 35
- Task 38 依赖 Task 35
- Task 41 依赖 Task 35, 36, 37, 38, 39, 40
- Task 43 依赖 Task 42（SECURITY_AUDIT 意图）
- Task 44 依赖 Task 42
- Task 45 依赖 Task 42
- Task 48 依赖 Task 42, 43, 44, 45, 46, 47
- Task 50 依赖 Task 49（CONCURRENCY 意图）
- Task 51 依赖 Task 50
- Task 53 依赖 Task 49, 50, 51, 52
- Task 55 依赖 Task 54（VISUALIZATION 意图）
- Task 58 依赖 Task 54, 55, 56, 57
- Task 60 依赖 Task 59（TEST_GENERATION 意图）
- Task 61 依赖 Task 60
- Task 63 依赖 Task 59, 60, 61, 62
- 阶段八~十二之间无依赖，可并行执行
