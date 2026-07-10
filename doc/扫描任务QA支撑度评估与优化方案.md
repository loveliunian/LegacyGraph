# 扫描任务 QA 支撑度评估与优化方案

> 项目：LegacyGraph 保证金项目（deposit-01）
> 评估日期：2026-07-10
> 基于扫描版本 #23（2,165 节点 / 2,811 边）及 QA v6.0（16 意图 / 10 大类问题）

## 摘要

当前扫描任务产出的图谱和文档**只能部分支撑 QA 问答需求**。核心矛盾在于：扫描已产出 40 种节点类型和 36 种边类型的完整数据模型，但实际入库的边远少于设计预期——大量节点因关键边缺失成为孤岛，QA 系统虽有 16 种意图处理链路，却因图谱连通性不足而无法回答多数类型的问题。同时，ADAPTER_SCAN 耗时逐次翻倍、大文档 OOM 跳过、文件扫描截断等性能问题进一步削弱了产出质量。

本方案基于对 code-review-graph、CodeGraph、LightRAG、Microsoft GraphRAG、Aider、Joern 等 12 个开源项目的调研，提出 **6 大优化方向、18 项具体措施**，按优先级分 4 个阶段递进实施。

## 1. 评估结论：扫描产出能否支撑 QA 问答

### 1.1 支撑度矩阵

| QA 问题类别 | 扫描产出支撑度 | 核心瓶颈 | 影响 |
|------------|-------------|---------|------|
| A 类 变更影响 | **70%** | Column→SqlStatement 反查链路基本可用，但前端 Page/Button 链路断裂 | 可回答表级影响，字段级精度不足 |
| B 类 实施方案 | **30%** | PROJECT_CONVENTION 向量未向量化；reusable 组件标记未执行 | 无法推荐可复用组件，方案脱离项目约定 |
| C 类 权限角色 | **20%** | GRANTS/ASSIGNED_TO 边未构建；Role 节点成孤岛 | 角色与权限链路完全断裂 |
| D 类 业务流程 | **25%** | BusinessProcess→Feature/ApiEndpoint 的 CONTAINS/IMPLEMENTS 边不完整 | 流程无法关联到具体接口 |
| E 类 数据血缘 | **15%** | DATA_FLOW 边未构建；正向遍历链路断裂 | 数据流向无法追踪 |
| F 类 测试影响 | **30%** | VERIFIED_BY 边仅在类级，未下沉到方法级 | 方法级测试反查不可用 |
| G 类 异常日志 | **40%** | Exception/LogPoint 节点已扫，THROWS/CATCHES/LOGS 边已建 | 基本可用，但覆盖面取决于 ExceptionExtractor 质量 |
| H 类 架构依赖 | **35%** | Package 节点已有，BELONGS_TO 边未建；DEPENDS_ON 边未从 import 语句构建 | 模块间依赖关系缺失 |
| I 类 接口契约 | **10%** | ApiEndpoint.properties 丢失 params/requestBody/responseType | 只能回答 URL，无法回答参数和返回值 |
| J 类 配置项 | **10%** | ConfigItem.properties 丢失 value/defaultValue | 无法回答配置值 |
| K 类 技术债 | **40%** | CALLS/READS/WRITES 边已有，可做循环依赖和扇入扇出 | 基本可用 |
| L 类 安全审计 | **20%** | SecurityRisk 节点未实际扫描；Column.sensitive 未标记 | 无法回答安全风险 |
| M 类 事务并发 | **20%** | TransactionScope 节点未实际扫描 | 无法回答事务边界 |

**综合支撑度：约 27%**——10 大类问题中仅 A 类（变更影响）和 G/K 类（异常/技术债）勉强可用，其余 7 类因图谱连通性或节点属性缺失而不可用。

### 1.2 根因分析

三大根本原因（与 QA Spec 诊断一致）：

1. **图谱已扫但未建边（占缺口 55%）**：Column、TestCase、BusinessProcess、Role、ConfigItem 等节点已入库，但 GRANTS、BELONGS_TO、CONTAINS、DATA_FLOW 等关键边缺失或零使用。节点成孤岛，QA 多跳查询无路可走。

2. **节点 properties 关键字段丢弃（占缺口 20%）**：ApiFact 抽取了 params/responseType、ConfigItemFact 抽取了 value/defaultValue，但 [GraphBuilder.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/builder/GraphBuilder.java) 创建节点时只存 displayName，契约信息和配置值丢失。

3. **扫描性能与完整性问题（占缺口 25%）**：837 个文件仅扫描 500 个（截断率 40%）、3 篇大文档 OOM 跳过、ADAPTER_SCAN 耗时翻倍导致扫描周期过长。这些直接导致图谱数据不完整，QA 依据不完整图谱回答必然出错。

## 2. 差距分析：扫描产出 vs QA 需求

### 2.1 边连通性缺口（最关键）

| 缺失边类型 | QA 依赖 | 应有来源 | 当前状态 | 影响 |
|-----------|---------|---------|---------|------|
| GRANTS (Role→Permission) | C 类权限 | RbacRoleExtractor 解析 @PreAuthorize SpEL | **未实现** | 权限链路完全断裂 |
| ASSIGNED_TO (Role→User) | C 类权限 | RbacUserExtractor | **未实现** | 用户角色关系缺失 |
| BELONGS_TO (Class→Package) | H 类架构 | PackageExtractor 解析 package 声明 | **未建边** | 模块聚合不可用 |
| DEPENDS_ON (Package→Package) | H 类架构 | import 语句分析 | **未实现** | 模块间依赖缺失 |
| DATA_FLOW (Table→下游) | E 类血缘 | SQL 写入目标追踪 | **未实现** | 数据血缘不可追踪 |
| CONTAINS (Process→Feature) | D 类流程 | BusinessGraphBuilder | **不完整** | 流程功能关联断裂 |
| IMPLEMENTS (Process→Api) | D 类流程 | BusinessGraphBuilder | **不完整** | 流程接口关联断裂 |
| VERIFIED_BY (Method→TestCase) | F 类测试 | 方法级测试关联 | **仅类级** | 方法级测试反查不可用 |

### 2.2 节点属性缺口

| 节点类型 | 丢失属性 | QA 依赖 | 当前状态 | 修复位置 |
|---------|---------|---------|---------|---------|
| ApiEndpoint | params, requestBody, responseType, summary | I 类接口契约 | ApiFact 已抽取，GraphBuilder 未写入 properties | [GraphBuilder.java#L66](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/builder/GraphBuilder.java) `buildApiNodes` |
| ConfigItem | value, defaultValue, className, fieldName | J 类配置项 | ConfigItemFact 已抽取，GraphBuilder 未写入 | [GraphBuilder.java#L2565](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/builder/GraphBuilder.java) `buildConfigItemGraph` |
| Column | sensitive | L 类安全审计 | ColumnMetadata 有 sensitive 字段但未写入 | [GraphBuilder.java#L651](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/builder/GraphBuilder.java) `buildColumnProperties` |
| Method | transactional, async, lockType | M 类事务并发 | 未从源码提取 | 需新增 ConcurrencyExtractor |

### 2.3 扫描完整性缺口

| 缺口 | 数据 | 影响 | 根因 |
|------|------|------|------|
| 文件截断 | 837 文件仅扫 500（截断 337） | 40% 代码未被分析 | DEFAULT_MAX_FILES=500 |
| 文档跳过 | 3 篇大文档 OOM（44KB/134KB/8.6KB） | 业务知识缺失 | LLM 抽取 + embedding 并发 OOM |
| 资产数限制 | maxDocs=50 | 文档覆盖不全 | DEFAULT_MAX_DOCS=50 |
| ADAPTER_SCAN 耗时翻倍 | 411s→1231s→2460s→1261s | 扫描周期过长 | Evidence queue full 290 次降级同步写 |

### 2.4 文档产出缺口

当前 4 份扫描文档（system-overview / scan-performance / code-understanding / external-tool-evidence）向量化后供 QA RAG 检索，但存在以下问题：

| 文档 | 问题 | 影响 |
|------|------|------|
| system-overview.md | 数据源依赖 lg_knowledge_claim 表，Claim 为空时回退到内置映射，内容空洞 | QA RAG 召回质量低 |
| scan-performance-report.md | 纯性能数据，无业务知识价值 | QA 无法从中获取有用信息 |
| code-understanding-report.md | 依赖 AI_CODE_UNDERSTANDING 步骤产出，该步骤默认关闭增强 | 内容不完整 |
| external-tool-evidence.md | 外部验证默认关闭（enabled=false），文档为空 | 无内容 |
| **缺失：PROJECT_CONVENTION** | 项目技术栈/分层规范/命名约定未向量化 | B 类实施方案无法获取项目约定 |

## 3. 开源项目对标分析

### 3.1 增量扫描对标

| 项目 | 核心技术 | 性能基准 | LegacyGraph 现状 | 差距 |
|------|---------|---------|----------------|------|
| code-review-graph (⭐19.4k) | SHA-256 文件哈希 + Blast Radius 传播分析 | 2900 文件重索引 <2s | 全量重扫，无增量 | **无增量能力** |
| CodeGraph (⭐58.9k) | OS 文件监听 + 防抖 + 过期标记 + 连接追赶 | 10k 文件 Token 减少 64% | 无文件监听 | **无实时同步** |
| LightRAG (⭐37.5k) | 局部图 + 集合合并增量更新 | 无需重建全局索引 | 每次全量重建 | **无局部图合并** |
| Aider | Tree-sitter + PageRank + Token 预算裁剪 | 大仓库压缩到 1k token | 无重要性排序 | **无符号级裁剪** |

### 3.2 图谱质量对标

| 项目 | 质量评估方法 | 指标 | LegacyGraph 现状 | 差距 |
|------|-----------|------|----------------|------|
| code-review-graph | 图派生 ground truth + git co-change 双评估 | F1=0.71, Recall=1.0 | 无质量评估 | **无任何指标** |
| LightRAG | 集成 RAGAS 框架 | Context Precision/Recall | 无 RAG 评估 | **无检索质量度量** |
| Microsoft GraphRAG | Leiden 社区检测 + 层次化摘要 | 社区覆盖率 | 无社区检测 | **无模块发现** |

### 3.3 可借鉴的核心实践

| 实践 | 来源 | 适用场景 | 预期收益 |
|------|------|---------|---------|
| SHA-256 文件哈希增量检测 | code-review-graph | 增量扫描 | 重扫耗时降低 90%+ |
| Blast Radius 传播分析 | code-review-graph | 变更影响范围 | QA A 类回答精度提升 |
| Tree-sitter 增量解析 | tree-sitter / Aider | AST 解析层 | 大文件解析速度提升 10-100x |
| 局部图 + 集合合并 | LightRAG | 增量图谱更新 | 无需全量重建 |
| PageRank 符号排序 | Aider | 大仓库裁剪 | Token 消耗降低 82x |
| 传递闭包边补全 | 知识图谱链路预测 | 边连通性修复 | GRANTS/CONTAINS/DATA_FLOW 缺失边补全 |
| Leiden 社区检测 | Microsoft GraphRAG | 模块发现 | H 类架构依赖自动发现 |
| RAGAS 评估框架 | LightRAG | QA 检索质量 | 量化 QA 回答质量 |
| LSP 交叉校验 | 代码导航最佳实践 | 边准确性验证 | 校验 CALLS/REFERENCES 边 |
| 角色化 LLM 配置 | LightRAG | 成本优化 | 抽取用强模型、查询用快模型 |

## 4. 优化方案

### 阶段一：图谱连通性修复（P0，立即执行）

**目标**：修复 QA Spec 已识别的边缺失和属性丢失问题，将 QA 综合支撑度从 27% 提升至 60%+。

#### 4.1.1 节点属性回填

**问题**：ApiEndpoint 和 ConfigItem 节点的关键 properties 字段在 GraphBuilder 中被丢弃。

**方案**：修改 [GraphBuilder.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/builder/GraphBuilder.java) 的 `buildApiNodes`（L66）和 `buildConfigItemGraph`（L2565），将 ApiFact 和 ConfigItemFact 中已抽取的字段写入节点 properties。

```java
// buildApiNodes 中补充：
properties.put("params", apiFact.getParams());
properties.put("requestBody", apiFact.getRequestBody());
properties.put("responseType", apiFact.getResponseType());
properties.put("summary", apiFact.getSummary()); // Swagger 注解

// buildConfigItemGraph 中补充：
properties.put("value", fact.getValue());
properties.put("defaultValue", fact.getDefaultValue());
properties.put("className", fact.getClassName());
properties.put("fieldName", fact.getFieldName());
```

**验收**：QA I 类问题"这个接口接收什么参数"可回答；J 类"配置项 xxx 的值是什么"可回答。

#### 4.1.2 GRANTS/ASSIGNED_TO 边构建

**问题**：Role 节点已扫描但 GRANTS（Role→Permission）和 ASSIGNED_TO（Role→User）边未构建，C 类权限问题完全不可用。

**方案**：在 [GraphBuilder.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/builder/GraphBuilder.java) `buildRbacRoleGraph`（L3920）中实现：
- 解析 `@PreAuthorize` / `@Secured` 注解中的 SpEL 表达式，提取权限字符串
- 构建 Role→Permission 的 GRANTS 边
- 从用户表/配置中提取 Role→User 的 ASSIGNED_TO 边
- 统一前后端 Permission nodeKey（小写化原值），避免重复节点

#### 4.1.3 BELONGS_TO/DEPENDS_ON 边构建

**问题**：Package 节点已有但 BELONGS_TO（Class→Package）边未建；DEPENDS_ON（Package→Package）边未从 import 语句构建。

**方案**：
- 在 PackageExtractor 中解析 Java `package` 声明，构建 Class→Package 的 BELONGS_TO 边
- 解析 `import` 语句，提取目标包路径，构建 Package→Package 的 DEPENDS_ON 边
- 借鉴 code-review-graph 的 Blast Radius：变更时通过 DEPENDS_ON 边追踪所有依赖者

#### 4.1.4 DATA_FLOW 边构建

**问题**：E 类数据血缘需要的 DATA_FLOW 边完全未构建。

**方案**：在 [GraphBuilder.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/builder/GraphBuilder.java) `buildMapperSqlGraph`（L361）中增强：
- JSqlParser 解析 INSERT/UPDATE/DELETE 语句的写入目标表
- 构建 Table→Table 的 DATA_FLOW 边（通过中间 SqlStatement 节点）
- 追踪 Service→Mapper→SqlStatement→Table 的完整数据流向

#### 4.1.5 业务图谱边补全

**问题**：BusinessProcess→Feature 的 CONTAINS 边和 BusinessProcess→ApiEndpoint 的 IMPLEMENTS 边不完整。

**方案**：在 [BusinessGraphBuilder.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/builder/BusinessGraphBuilder.java) 中：
- 移除孤立逻辑，确保 buildBusinessGraph 构建的 Process→Feature CONTAINS 边完整
- 补全 mapBusinessProcessesToApis 的 IMPLEMENTS 边
- 阈值沿用定稿值（API=0.6, Page=0.55, token=0.2）

### 阶段二：扫描性能与完整性修复（P1，立即执行）

**目标**：消除 OOM、解决文件截断、修复 ADAPTER_SCAN 耗时翻倍，确保扫描数据完整。

#### 4.2.1 文件截断修复

**问题**：837 文件仅扫 500 个，截断率 40%。

**方案**：修改 [ScanScopeResolver.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/service/scan/ScanScopeResolver.java)：
- DEFAULT_MAX_FILES 从 500 提高到 2000
- DEFAULT_MAX_DOCS 从 50 提高到 200
- 增加配置项 `legacygraph.scan.max-files` 和 `legacygraph.scan.max-docs` 允许动态调整

#### 4.2.2 大文档 OOM 根治

**问题**：134KB 文档 LLM 抽取 + embedding 并发 OOM。

**方案**：在 [DocExtractStep.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/step/DocExtractStep.java) 中：
- 对 >100KB 文档前置截断至 50KB（保留前 50KB 语义最密集部分）
- 对 >50KB 文档使用更小 chunk（800 字符）
- LLM 抽取和 embedding 严格串行化（LLM 完成后再提交向量化）
- 提升 LLM 内存水位线至 0.60

#### 4.2.3 ADAPTER_SCAN 耗时翻倍修复

**问题**：Evidence queue full 290 次降级同步写，每个线程阻塞 5s+，严重拖慢。

**方案**：在 [PgEvidenceTxExecutor.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/builder/PgEvidenceTxExecutor.java) 中：
- 队列容量从 2000 提高到 8000
- 增加批量写入：每 100 条 evidence 合并为一次 DB 事务
- worker 内增加内存水位检查（>85% 跳批但记录，不降级同步写）
- 增加队列消费线程数从 1 提高到 2

#### 4.2.4 AI_FEATURE_MAPPING LLM 质量提升

**问题**：7 个 LLM batch 中仅 1 个产出映射，其余返回空。

**方案**：优化 [FeatureMappingStep.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/step/FeatureMappingStep.java) 的 prompt：
- 在 prompt 中增加代码上下文（不只是 Feature 名称列表，附带 Controller/ApiEndpoint 的 summary）
- 减少 batch size 从 80 降到 40，降低 LLM 上下文负担
- 增加 few-shot 示例（成功映射的案例）
- 对返回空映射的 batch 自动重试一次（换用更强模型）

### 阶段三：增量扫描与图谱质量（P2，近期规划）

**目标**：引入增量扫描能力，建立图谱质量评估体系。借鉴 code-review-graph、CodeGraph、LightRAG 的最佳实践。

#### 4.3.1 文件级增量扫描

**借鉴**：code-review-graph 的 SHA-256 文件哈希 + CodeGraph 的三层同步机制。

**方案**：
- 新增 `FileChangeDetector` 服务，扫描时记录每个文件的 SHA-256 哈希到 `lg_file_snapshot` 表
- 重扫时对比哈希，仅对变更文件重新执行 ExtractionAdapter
- 对变更文件执行 Blast Radius 分析：图遍历找所有依赖者（CALLS/READS/WRITES/REFERENCES 边的源节点），标记为"受影响"节点
- 仅重新构建受影响子图，复用 LightRAG 的局部图+集合合并策略
- 预期：重扫耗时从 ~40 分钟降至 <5 分钟（仅变更文件）

#### 4.3.2 Tree-sitter 增量解析层

**借鉴**：tree-sitter 增量解析 + Aider 的 tree-sitter queries。

**方案**：
- 引入 Tree-sitter Java/JS/Python 解析器替代部分正则提取
- 用 tree-sitter queries 统一定义"函数/类/import/调用"的节点提取规则
- 支持增量解析：文件变更后仅重解析变更的 AST 子树
- 借鉴 code-review-graph 的 `languages.toml` 模式：新语言扩展只需加配置不改代码

#### 4.3.3 图谱质量评估框架

**借鉴**：code-review-graph 的双模式评估 + LightRAG 的 RAGAS 集成。

**方案**：
- 新增 `GraphQualityAssessor` 服务，扫描完成后自动评估：
  - **完整性（Coverage）**：各节点类型/边类型的覆盖率（图中数量 ÷ 源码应有数量）
  - **连通性（Connectivity）**：孤立节点比例、平均连通度、最大连通子图占比
  - **准确性（Precision）**：随机抽样 N 条边，与 LSP find-references 结果对比
  - **一致性（Consistency）**：本体约束校验（如"Method 必须属于某个 Class"、"Table 必须有 Column"）
- 输出质量报告到 `docs/legacygraph/graph-quality-report.md`
- 质量分数低于阈值时自动触发缺口补扫

#### 4.3.4 边补全与实体对齐

**借鉴**：知识图谱链路预测 + 传递闭包。

**方案**：
- **传递闭包补全**：import 链传递依赖（A imports B, B imports C → A 间接依赖 C）
- **规则校验补全**：检查本体约束，缺失边标记为 PENDING_CONFIRM 并尝试补全
- **LSP 交叉校验**：对 Java 项目，可选启动 JDTLS 获取精确 find-references，校验/补全 CALLS 边
- **实体对齐增强**：在 [BusinessGraphBuilder.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/builder/BusinessGraphBuilder.java) 的名称相似度映射基础上，增加图结构对齐（GNN 学习结构特征）

### 阶段四：文档质量与 QA 增强（P3，中期规划）

**目标**：提升扫描文档的知识密度，增强 QA RAG 检索质量。

#### 4.3.5 PROJECT_CONVENTION 向量化

**问题**：B 类实施方案需要的项目约定未向量化。

**方案**：在 [ProjectConventionIngestService](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/service/scan/ScanArtifactPublisher.java) 中：
- 扫描后自动提取项目技术栈（pom.xml/package.json）、分层规范（目录结构）、命名约定
- 向量化为 chunkType=PROJECT_CONVENTION
- QA IMPLEMENTATION_PLAN 意图检索时优先召回

#### 4.3.6 可复用组件标记

**问题**：B 类实施方案无法推荐可复用组件。

**方案**：在扫描后增加 `ReusableComponentMarker` 步骤：
- 统计每个 Class 被继承次数（EXTENDS 边的入度）
- 被继承 ≥2 次的标记 properties.reusable=true
- 记录 properties.extendedBy 列表

#### 4.3.7 Leiden 社区检测

**借鉴**：Microsoft GraphRAG 的 Leiden 社区检测。

**方案**：
- 扫描后对图谱执行 Leiden 算法，自动发现代码模块/子系统
- 将社区检测结果写入 Package 节点的 properties.community 字段
- QA H 类架构依赖问题可直接查询社区结构

#### 4.3.8 system-overview.md 内容增强

**问题**：当前 system-overview.md 依赖 knowledge_claim 表，Claim 为空时内容空洞。

**方案**：
- 从图谱直接查询四层结构（业务层/功能层/代码层/数据层）的节点和边
- 增加核心贯穿链路（Table→SqlStatement→Mapper→Service→Controller→ApiEndpoint→Page）
- 增加图谱统计摘要（节点类型分布、边类型分布、连通性指标）
- 增加模块依赖 Mermaid 图（基于 DEPENDS_ON 边）

## 5. 实施优先级与预期收益

| 优先级 | 阶段 | 措施数 | 预期 QA 支撑度 | 预期扫描耗时 |
|-------|------|--------|-------------|-----------|
| P0 立即 | 阶段一：图谱连通性修复 | 5 项 | 27% → 65% | 不变 |
| P1 立即 | 阶段二：性能与完整性修复 | 4 项 | 65% → 75% | -40% |
| P2 近期 | 阶段三：增量扫描与质量 | 4 项 | 75% → 85% | -90%（重扫） |
| P3 中期 | 阶段四：文档与 QA 增强 | 4 项 | 85% → 90%+ | 不变 |

## 6. 风险与依赖

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| Tree-sitter 引入增加依赖体积 | JAR 包增大 ~5MB | 仅引入 Java/JS 解析器，按需加载 |
| LSP 交叉校验需启动语言服务器 | 资源消耗高 | 设为可选步骤，默认关闭 |
| Leiden 社区检测对大图耗时 | 扫描后增加 10-30s | 异步执行，不阻塞扫描完成 |
| 增量扫描的哈希对比开销 | 首次扫描无收益 | 首次扫描记录哈希，后续扫描收益 |
| 边补全可能引入误报 | 图谱噪声增加 | 补全的边标记为 PENDING_CONFIRM，confidence=0.85 |
| BREAKING：Column nodeKey 统一 | 旧 schema 前缀 Column 节点变孤立 | 重新扫描或清理 STALE 节点 |

## 7. 结论

当前扫描任务的产出**尚不足以全面支撑 QA 问答需求**，核心瓶颈不在数据模型设计（已覆盖 40 节点类型 / 36 边类型 / 16 意图），而在**图谱连通性**（关键边缺失导致节点孤岛）和**扫描完整性**（文件截断 / 文档跳过 / 性能退化）。

优先实施阶段一（图谱连通性修复）和阶段二（性能修复），可在短期内将 QA 支撑度从 27% 提升至 75%。阶段三引入增量扫描后，重扫耗时可降低 90%以上，同时建立图谱质量评估体系形成闭环。阶段四的文档增强和社区检测将进一步提升 QA 回答质量。

开源项目调研的核心启示：**code-review-graph 的 SHA-256 增量检测 + Blast Radius 传播分析**是最高性价比的优化方向，可直接解决 ADAPTER_SCAN 耗时翻倍和全量重扫问题；**LightRAG 的局部图+集合合并**策略为增量图谱更新提供了成熟方案；**Microsoft GraphRAG 的 Leiden 社区检测**可自动发现模块结构，弥补手动 Package 提取的不足。
