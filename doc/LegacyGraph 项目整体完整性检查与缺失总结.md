# LegacyGraph 项目整体完整性检查与缺失总结

> 检查日期：2026-06-29  
> 最后更新：2026-06-29（再次核验版）  
> 检查范围：三类图谱的方法论、三类图谱的具体实现、三类图谱的落地计划、后端代码、前端代码、数据库脚本、CI 与部署配置  
> 检查口径：以当前代码、当前路由、当前测试结果为准；旧文档和上一版总结只作为线索，不作为完成事实  
> 代码分析方式：优先使用 codebase-memory-mcp 图谱索引和调用追踪，再用文本搜索补充字符串、配置、测试报告和非代码文档  

---

## 一、总体结论

LegacyGraph 的平台骨架已经比较完整：后端有扫描、抽取、图谱构建、图谱查询、LLM Agent、向量、审核、测试、报告、系统管理等模块；前端有项目、数据源、图谱、事实证据、审核、测试、报告、迁移风险、系统管理等页面；数据库初始化脚本覆盖 31 张表；Docker Compose 覆盖 PostgreSQL、Neo4j、Redis、MinIO、后端和前端。

但本次重新核验后，不能继续沿用“可交付 MVP”或“核心闭环全部打通且已验证”的结论。当前更准确的判断是：

1. 代码图谱和功能图谱的后端扫描主链路基本打通。
2. 业务图谱有 Agent 和 Builder，但 `BusinessGraphBuilder.buildBusinessGraph` 当前没有被主流程或文档解析接口调用，业务事实到业务图谱落库没有闭环。
3. 证据层的 Builder 写入逻辑存在，但事实与证据的关联查询仍有 ID 口径风险。
4. LLM 和向量后端主实现存在，但当前测试未通过，且向量前后端接口路径不一致。
5. 运行链路图谱仍是静态前端示例，没有后端 trace ingestion。
6. 当前后端 `mvn test` 失败，前端 `npm run type-check` 失败，因此项目不能按“已验证可交付”定性。

综合评估：**项目处于“核心模块覆盖较全，但集成验证未收口”的阶段。工程骨架约 80%-85%，三类图谱方法论核心闭环约 60%-70%，生产就绪度约 45%-55%。**

---

## 二、与三类图谱方法论的对齐情况

三份图谱文档的共同要求可以归纳为：

1. 先构建统一项目知识图谱，再投影业务图谱、功能图谱、代码图谱，不能做三个孤立图谱。
2. 节点和关系必须可追溯到证据，包含来源、置信度和审核状态。
3. 代码、SQL、数据库元数据、运行记录是事实来源；AI 只做抽取、归纳、匹配和补全。
4. 功能链路应贯通：业务能力 -> 功能 -> 页面/按钮 -> API -> Controller -> Service -> Mapper -> SQL -> 表。
5. 自动测试应从图谱生成，测试结果和人工审核应反向回写图谱置信度和状态。
6. 运行时 trace、日志、链路采样属于增强补证能力，必须明确是否实现。

### 方法论对照表

| 方法论要求 | 当前代码事实 | 完整性判断 |
|---|---|---|
| 统一总图 | `GraphNode`、`GraphEdge`、`Neo4jSyncService`、`GraphQueryService.getUnifiedGraph` 存在，后端有统一图查询接口 | 基础成立 |
| 代码图谱 | `ProjectScanner.startFullScan` 会调用 Java Controller、MyBatis XML、数据库元数据、前端文件、Service 调用扫描；`buildServiceCallGraph` 已接入扫描链路 | 基本闭合 |
| 功能图谱 | `FrontendGraphBuilder.buildFrontendApiGraph` 已被 `scanFrontendFiles` 调用，前端 API 到后端 API 的匹配逻辑存在 | 后端链路基本闭合，前端类型和页面集成未验证 |
| 业务图谱 | `DocUnderstandingAgent.extractBusinessFacts`、`BusinessGraphBuilder.buildBusinessGraph` 存在 | 未闭环：`buildBusinessGraph` 当前无主流程调用者 |
| 证据层 | `lg_evidence`、`lg_node_evidence`、`lg_edge_evidence` 存在，三类 Builder 均有 `createEvidenceForNode`/`createEdge` 逻辑 | 主体存在，但事实到证据查询仍有口径问题 |
| 测试生成与执行 | `TestCaseService`、`ApiTestExecutor`、`DbAssertionExecutor`、`E2eTestExecutor`、`TestExecutionScheduler` 存在 | 实现存在，但当前测试门禁失败 |
| 测试回写 | `TestResultUpdateService.updateConfidenceByTestResults` 被测试回调、验证接口和调度器调用；失败会创建 `ReviewRecord` | 链路存在，未通过测试验证 |
| 人工审核 | `ReviewController` 有 pending/history/detail/confirm/reject/batch-confirm，前端有对应 API | 基本可用，仍受前端类型失败影响 |
| 运行时图谱 | 前端 `RuntimeGraph.vue` 是本地静态 trace/service/slowRequests 数组，后端无 OTel/trace ingestion | 未实现 |
| LLM | `LlmGateway` 调用 Spring AI `OpenAiChatModel`，有模板渲染、PromptRun 记录、temperature、JSON 解析 | 实现存在，当前测试失败，脱敏服务未统一接入 |
| 向量检索 | `VectorizationService`/`VectorRetrievalService` 使用 `EmbeddingModel.embed()` 和 pgvector repository 查询 | 后端实现存在，但前后端接口路径不一致，当前测试失败 |

---

## 三、后端完整性检查

### 当前规模

| 类别 | 当前数量 | 说明 |
|---|---:|---|
| Controller | 15 | 覆盖项目、数据源、扫描、事实、图谱、Agent、审核、测试、报告、认证、系统管理、向量等 |
| Service | 16 | 覆盖项目、扫描版本、图谱查询/合并/验证、测试、报告、向量、系统、审计等 |
| Entity | 31 | 与 `docs/sql/init.sql` 的 31 张表基本对应 |
| Repository | 32 | MyBatis-Plus Mapper 为主 |
| 后端测试文件 | 54 | 实际 surefire 本次生成 46 个报告，部分测试目录/类被 POM 排除 |

### 已实现且可保留的事实

| 模块 | 代码事实 | 状态 |
|---|---|---|
| 扫描主链路 | `startFullScan` 调用 `scanJavaControllers`、`scanMyBatisXml`、`scanDatabaseMetadata`、`scanFrontendFiles`、`scanServiceCalls`、`syncToNeo4j` | 已接入 |
| Service 调用图 | `buildServiceCallGraph` 的调用链为 `ScanController.start` -> `ProjectScanner.startFullScan` -> `scanServiceCalls` -> `buildServiceCallGraph` | 已接入 |
| 前端 API 图 | `buildFrontendApiGraph` 的调用链为 `startFullScan` -> `scanFrontendFiles` -> `buildFrontendApiGraph` | 已接入 |
| 图谱查询 | `GraphQueryController` 提供 api-chain、table-impact、feature-view、business-view、unified、merge 等接口 | 已实现 |
| 测试回写 | `updateConfidenceByTestResults` 会根据结果调用 `onTestPass/onTestFail`；失败会降分并创建审核记录 | 链路存在 |
| 审核 | `ReviewController` 支持待审核、历史、详情、确认、驳回、批量确认 | 基本完整 |
| 报告导出 | `ReportExportController` 和 `ReportExportService` 支持图谱质量/测试覆盖/迁移报告导出 | 实现存在 |
| 向量后端 | `VectorizationService.embedAndStore`、`VectorRetrievalService.semanticSearch/findSimilarNodes` 已使用 `EmbeddingModel.embed()` | 主路径已实现 |
| LLM 网关 | `LlmGateway.callWithTemplate` 有模板渲染、OpenAI 调用、PromptRun 状态记录和 JSON 清洗解析 | 主路径已实现 |

### 当前后端关键缺口

| 优先级 | 缺口 | 证据 | 影响 |
|---|---|---|---|
| P0 | 后端测试门禁失败 | 本次执行 `mvn test -q`：46 个 surefire 报告，359 tests，92 failures，221 errors | 不能宣称后端已验证可交付 |
| P0 | 业务图谱未自动落库 | `BusinessGraphBuilder.buildBusinessGraph` 无生产调用者；`DocUnderstandingAgent.extractBusinessFacts` 只被 `FactController`/`LlmAgentController` 调用并返回结果 | 业务文档 -> 业务图谱的落地链路断开 |
| P0 | 向量前后端路径不一致 | 后端 `VectorController` 是 `/api/projects/{projectId}/vector/...`；前端 `vector.api.ts` 调用 `/lg/vector/projects/{projectId}/...` | 前端向量检索不可用 |
| P1 | Fact 与 Evidence 关联查询口径疑似错误 | `FactController.getRelatedNodes` 用 factId 查询 `NodeEvidence.evidenceId`，而 `Fact` 自身有 `evidenceIds` 字段 | 事实详情页可能查不到真实关联节点 |
| P1 | PII 脱敏服务未接入 LLM 网关 | `PiiMaskingService.mask` 无生产调用者；`LlmGateway` 使用本地正则 `maskSensitiveData` | 脱敏能力不统一，覆盖范围不一致 |
| P1 | 运行时 trace 后端缺失 | 只有 `LogAspect` 自生成审计 traceId，无 OpenTelemetry/trace ingestion/trace graph repository | 运行链路图谱不可交付 |
| P1 | 测试配置信号不足 | `pom.xml` 对 `**/agent/**`、`**/builder/**`、`**/llm/**` 等测试有排除配置；实际新增测试仍大量失败 | CI 存在但当前不会形成可信绿灯 |
| P2 | 抽取器仍有近似实现 | `VueRouteExtractor` 使用正则/近似 JSON；`MyBatisXmlExtractor` 有 include/行号 TODO；`SqlTableExtractor` 有更多 FromItem TODO | 复杂老项目抽取准确率有限 |
| P2 | 批量测试生成仍未完整 | `TestCaseController` 中仍有“完整实现批量按范围生成” TODO | 批量生成能力不完整 |

---

## 四、前端完整性检查

### 当前规模与已具备模块

| 类别 | 当前数量/状态 | 说明 |
|---|---:|---|
| Vue 页面 | 36 | 覆盖 dashboard、project、source、graph、fact、review、test、migration、system、audit 等 |
| 图谱路由 | 已注册 | 代码图、统一图、业务图、功能图、数据血缘、运行链路均在路由中 |
| API 封装 | 已覆盖多数模块 | project/source/graph/fact/review/test/report/vector 等 API 文件存在 |
| 图谱组件 | 已存在 | `GraphViewer`、`GraphViewerOptimized`、`CustomNode`、`GraphToolbar`、`NodeDetailDrawer` 等 |
| 证据组件 | 已存在 | `EvidencePanel` 已用于统一图证据展示 |

### 已实现或部分实现的前端事实

| 页面/模块 | 当前状态 | 判断 |
|---|---|---|
| `UnifiedGraph.vue` | 调用统一图接口，支持版本、过滤、证据、审核动作 | 主体可用，但类型检查仍失败 |
| `DataLineageGraph.vue` | 会从后端统一图加载表，再调用 `getTableImpact`；失败时保留本地示例 | 部分可用，路由 badge 仍是“建设中” |
| `FactList.vue` / `EvidenceSearch.vue` | 已转为调用后端事实/证据接口 | 方向正确，依赖后端关联口径 |
| `ReviewList.vue` / `ReviewHistory.vue` | 已有后端 API 封装和页面调用 | 方向正确 |
| `RiskList.vue` / `RiskDetail.vue` | 有报告/图谱/审核相关调用 | 部分可用 |
| `CodeGraph.vue` | 仍保留“加载示例”静态工单派发图 | 示例能力存在，不等于真实图谱页面完全闭合 |
| `RuntimeGraph.vue` | 完全使用本地静态 traces/services/slowRequests | 不可交付 |
| audit 日志页 | 仍有 mock 数据 | 不可按真实审计闭环认定 |

### 当前前端关键缺口

| 优先级 | 缺口 | 证据 | 影响 |
|---|---|---|---|
| P0 | 前端类型检查失败 | 本次执行 `npm run type-check` 失败，错误覆盖 chart、graph、stores、locales、request、BusinessGraph、FeatureGraph、UnifiedGraph、system、test 等模块 | 不能宣称前端构建质量已收口 |
| P0 | 向量 API 路径不匹配 | 前端 `/lg/vector/projects/...` vs 后端 `/api/projects/.../vector/...` | 向量页面或调用方不可用 |
| P1 | API 返回类型与 request 解包不一致 | 多处类型错误显示把 `AxiosResponse` 当业务对象使用，或业务对象被当 `AxiosResponse` 使用 | 影响图谱、项目、dashboard 等页面 |
| P1 | 图谱组件类型与 Vue Flow/G6 版本不匹配 | `GraphViewer`、`GraphViewerOptimized`、`BusinessGraph`、`FeatureGraph` 大量类型错误 | 图谱页面存在运行和维护风险 |
| P1 | 运行链路页面仍为本地静态数据 | `RuntimeGraph.vue` 中 traces/services/slowRequests 为固定数组，刷新只弹消息 | 运行时图谱不可交付 |
| P2 | 数据血缘仍标“建设中”且有示例兜底 | 路由 meta badge 为“建设中”；加载失败使用本地示例 | 用户会混淆真实数据和演示数据 |
| P2 | 多处基础组件缺少导入或类型声明 | 如 `ref`、`computed`、`watch`、`ElMessage` 等错误 | 说明前端基础类型债较多 |

---

## 五、数据库、部署与 CI 检查

### 数据库

`docs/sql/init.sql` 当前包含 31 个 `CREATE TABLE`，覆盖：

项目、代码仓库、数据库连接、文档、扫描版本/任务、事实、图谱节点/关系、证据、节点证据、边证据、文档分片、向量文档、测试用例/断言/结果/运行、审核记录、迁移风险、LLM provider、Prompt 模板、Prompt 运行、报告、系统操作日志、用户、角色、字典、配置等。

需要继续核验：

1. 31 个 Entity 与 31 张表是否逐字段一致。
2. `Fact.evidenceIds`、`Evidence.relatedNodeIds`、`NodeEvidence`、`EdgeEvidence` 的事实/证据/节点关联口径是否统一。
3. `vector_document` 的 repository 参数类型、版本字段和前端接口路径是否一致。

### 部署

`deploy/docker-compose.yml` 覆盖 PostgreSQL + pgvector、Neo4j、Redis、MinIO、backend、frontend。配置结构完整，但本次未执行 `docker-compose up --build`，不能宣称全栈部署已验证。

### CI

`.github/workflows/ci.yml` 已存在，包含后端 compile/test 与前端 install/type-check/build/unit test。但当前本地核验结果为：

```bash
cd backend
mvn test -q
# 失败：46 个 surefire 报告，359 tests，92 failures，221 errors

cd frontend
npm run type-check
# 失败：大量 TypeScript 错误，覆盖图谱组件、API 类型、store、系统页、测试页等
```

因此 CI 文件存在，不等于 CI 真实可通过。当前最重要的工程结论是：**测试与类型门禁尚未收口。**

---

## 六、三类图谱落地计划完成度重估

| 能力 | 当前状态 | 完成度估算 | 说明 |
|---|---|---:|---|
| 项目/数据源/扫描基础 | 基本实现 | 75%-85% | CRUD 与扫描框架存在，测试失败降低可信度 |
| 代码图谱 | 主链路接入 | 75%-80% | Controller、Mapper、SQL、DB、Service 调用均有抽取/构建入口 |
| 功能图谱 | 后端构建接入 | 65%-75% | 页面/API/后端 API 匹配存在，前端页面和类型未收口 |
| 业务图谱 | 未闭环 | 45%-55% | Agent 与 Builder 存在，但业务事实到图谱落库未接主流程 |
| 证据溯源 | 主体存在 | 70%-80% | Builder 写 evidence，但 Fact/Evidence/NodeEvidence 查询口径需修 |
| LLM | 实现存在 | 55%-65% | Spring AI 调用存在，当前测试失败，脱敏服务未统一接入 |
| 向量检索 | 后端主路径存在 | 55%-65% | embedding + pgvector 实现存在，前后端路径不一致，测试失败 |
| 自动测试生成/执行 | 实现存在 | 45%-55% | 服务和执行器存在，但 `mvn test` 大面积失败 |
| 测试回写/审核 | 链路存在 | 60%-70% | 回写和审核记录逻辑存在，需测试修复确认 |
| 报告导出 | 实现存在 | 60%-70% | 服务和 Controller 存在，当前相关测试失败 |
| 数据血缘 | 部分可用 | 55%-65% | 已接后端表影响接口，但仍标建设中且有示例兜底 |
| 运行链路 | 未实现 | 10%-15% | 只有前端静态页面，无后端 trace |
| 生产就绪 | 未达到 | 45%-55% | 后端测试和前端类型检查均失败 |

---

## 七、当前最优先缺口清单

### P0：必须先收口

| 编号 | 任务 | 验收标准 |
|---|---|---|
| P0-1 | 修复后端测试门禁 | `cd backend && mvn test` 通过；同时复核 POM 测试排除是否合理 |
| P0-2 | 修复前端类型门禁 | `cd frontend && npm run type-check` 通过；再跑 `npm run build` |
| P0-3 | 接通业务事实到业务图谱落库 | 文档解析或 Agent 运行后调用 `BusinessGraphBuilder.buildBusinessGraph(projectId, versionId, facts)` 并能查询到业务域/流程/对象/规则节点 |
| P0-4 | 统一向量 API 路径 | 前端 `vector.api.ts` 与后端 `VectorController` 路径一致，并补前后端联调测试 |
| P0-5 | 修正 Fact 关联节点查询 | `FactController.getRelatedNodes` 按 `Fact.evidenceIds` 查证据，再通过 `NodeEvidence` 找节点，或补明确的 fact-node 关联表 |

### P1：核心闭环增强

| 编号 | 任务 | 验收标准 |
|---|---|---|
| P1-1 | 将 `PiiMaskingService` 接入 `LlmGateway` | 所有 LLM prompt 入库前统一脱敏，覆盖 API Key、JDBC、密码、邮箱、手机、IP 等 |
| P1-2 | 清理前端 API 类型和 request 解包模型 | 页面拿到的类型与 `request` 返回 `res.data` 的行为一致，不再出现 AxiosResponse/业务对象混用 |
| P1-3 | 修复图谱组件与 Vue Flow/G6 类型适配 | `GraphViewer`、`BusinessGraph`、`FeatureGraph` 类型通过，节点点击/聚焦/布局 API 与库版本一致 |
| P1-4 | 移除或隔离演示数据 | `CodeGraph` 示例、`DataLineageGraph` fallback、audit mock 必须明确标注 demo 或替换为真实接口 |
| P1-5 | 补业务图谱集成测试 | 验证文档事实抽取 -> 业务图谱节点/边 -> 证据关联 -> 业务视图查询 |
| P1-6 | 补向量端到端测试 | 验证文档分片 -> embedding 存储 -> semantic search -> similar nodes |

### P2：二期能力

| 编号 | 任务 | 验收标准 |
|---|---|---|
| P2-1 | 运行时 trace ingestion | 接入 OpenTelemetry 或日志采样，形成 trace/span -> graph edge 的后端模型 |
| P2-2 | 抽取器增强 | Vue 路由从正则升级 AST/编译器解析；MyBatis include 和行号解析补齐；SQL FromItem 覆盖更多类型 |
| P2-3 | 图谱质量度量体系 | 输出覆盖率、证据完备度、待审核比例、测试通过率、运行时验证比例 |
| P2-4 | Docker 全栈验证 | `docker-compose up --build` 后服务健康检查、扫描、查询、LLM/向量配置均可验证 |

---

## 八、建议执行顺序

1. 先修后端 `mvn test`。当前失败数量太大，任何“已完成”判断都应以测试门禁恢复为前置。
2. 并行修前端 `npm run type-check`。优先处理 request 类型、Vue Flow/G6 类型和缺失导入，这些会影响多页面。
3. 接通业务图谱 Builder。把 `DocUnderstandingAgent` 的输出真正落到 `GraphNode/GraphEdge/Evidence`，并补集成测试。
4. 修向量接口路径和端到端测试。后端实现已存在，主要风险在前后端路径、版本参数和 similar nodes 的 sourceUri 映射。
5. 修事实/证据关联查询。统一 `Fact.evidenceIds`、`Evidence.relatedNodeIds`、`NodeEvidence` 的关系口径。
6. 再做运行时链路。该能力属于 V2，不应阻塞静态代码/功能/业务三类图谱 MVP，但必须继续标为未实现。

---

## 九、最终结论

LegacyGraph 已经不是空壳项目，代码图谱和功能图谱的后端主链路也不是 placeholder。当前项目的真实问题在于：**实现面铺得很广，但关键集成验证和若干跨层链路没有收口。**

当前不能再写“可交付 MVP”。更准确的结论是：

1. **代码图谱：基本可进入修测试和精度增强阶段。**
2. **功能图谱：后端构建链路已接入，但前端图谱页面和 API 类型需要先过类型门禁。**
3. **业务图谱：Agent 和 Builder 都有，但自动落库主链路缺失，是三类图谱中最大的闭环缺口。**
4. **证据/审核/测试回写：设计和代码主路径存在，但后端测试失败，必须先恢复验证可信度。**
5. **LLM/向量：后端实现存在，但当前测试失败、路径不一致和脱敏未统一接入需要修复。**
6. **运行链路：仍是 V2 能力，当前不可交付。**

因此，项目下一阶段的目标不应再是继续补页面或堆功能，而应是：**先让后端测试和前端类型检查变绿，再补业务图谱落库、向量路径、事实证据关联这三条断链。**
