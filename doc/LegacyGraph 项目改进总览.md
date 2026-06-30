# LegacyGraph 项目改进总览（合并版）

> 本文档由以下五份文档合并而成，按"项目现状 → 改进点与进展 → 剩余缺口 → 演进历史"重新组织，作为项目改进的唯一权威记录：
> - `LegacyGraph 下一步实施计划.md`（门禁/三条断链/trace/度量 实施轨道）
> - `LegacyGraph 下一步优化方案.md`（图谱闭环/查询正确性 实施轨道）
> - `LegacyGraph 项目整体完整性检查与缺失总结.md`（完整性核验）
> - `未完成功能清单.md`（早期功能清单）
> - `项目整体缺口审计报告.md`（早期缺口审计）
>
> 最后更新：2026-06-30　|　口径：以当前源码与可验证测试结果为准，旧文档结论仅作演进线索

---

## 第一部分：项目现状

### 1.1 一句话结论

LegacyGraph 已完成主要工程骨架、三类图谱获取链路与核心集成验证收口。**两条门禁均绿**，三条跨层断链已接通，运行时链路后端已落地。当前处于"核心闭环打通、待真实数据与全栈部署做最终验证"的阶段。

### 1.2 门禁实测结果

```bash
cd backend && mvn clean test
# 通过：379 tests, 0 failures, 0 errors, 2 skipped, BUILD SUCCESS
#       （已解除对 agent/builder/llm 的全部测试排除）

cd frontend && npm run type-check && npm run build
# 通过：type-check exit 0，build exit 0
```

### 1.3 当前规模

| 类别 | 数量 | 说明 |
|---|---:|---|
| 后端 Controller | 17 | 项目、数据源、扫描、事实、图谱、Agent、审核、测试、报告、认证、系统、向量、运行时链路等 |
| 后端 Entity | 32 | 与 `docs/sql/init.sql` 的 32 张表对应（新增 `lg_runtime_trace`） |
| 后端测试类 | 55 | `mvn clean test` 379 用例全绿 |
| 数据库表 | 32 | init.sql 覆盖项目/仓库/连接/文档/扫描/事实/图谱/证据/向量/测试/审核/迁移/LLM/报告/系统/运行时链路等 |
| Vue 页面 | 36 | dashboard/project/source/scan/graph/fact/review/test/migration/system/audit 等 |
| Docker Compose | 2 服务（应用） | backend、frontend；PG/Neo4j/Redis/MinIO 用外部服务器，经 `.env` 注入 |

### 1.4 三类图谱方法论对齐

| 方法论要求 | 当前事实 | 状态 |
|---|---|---|
| 统一总图 | `GraphNode`/`GraphEdge`/`Neo4jSyncService`/`GraphQueryService.getUnifiedGraph` | ✅ 成立 |
| 代码图谱 | `startFullScan` 扫 Controller/MyBatis XML/DB 元数据/前端文件/Service 调用；抽取器已增强 | ✅ 闭合 |
| 功能图谱 | `buildFrontendApiGraph` 接入扫描；前端 API↔后端 API 匹配（含参数个数相似性打分） | ✅ 后端闭合，前端类型门禁绿 |
| 业务图谱 | `extractBusinessFacts → buildBusinessGraph` 在 `FactController.extractDocFacts` 接通落库，并触发 `mapFeaturesToCode` | ✅ 已闭环（含集成测试） |
| 证据层 | 三类 Builder 写 evidence/node-evidence；`getRelatedNodes` 按来源解析关联节点 | ✅ 闭合 |
| 测试生成/执行 | `TestCaseService`/`ApiTestExecutor`（含 LOGIN 取 token）/`DbAssertionExecutor`/`E2eTestExecutor`/`TestExecutionScheduler` | ✅ 实现，门禁绿 |
| 测试回写/审核 | `updateConfidenceByTestResults` + `ReviewController` | ✅ 链路存在，测试通过 |
| 运行时图谱 | `TraceController` + `TraceIngestionService` + `lg_runtime_trace`；前端 `RuntimeGraph.vue` 接真实拓扑 | ✅ 后端落地，待真实 span 验证 |
| LLM | `LlmGateway` + Spring AI + 模板渲染 + PromptRun + 统一 `PiiMaskingService` 脱敏 | ✅ 实现，测试通过 |
| 向量检索 | `VectorizationService`/`VectorRetrievalService` + pgvector；前后端路径统一 | ✅ 实现，含端到端测试 |
| 图谱质量度量 | `GraphMetricsReport` + `generateGraphMetrics`（覆盖率/证据完备度/待审核比例/测试通过率/运行时验证比例） | ✅ 已实现 |

---

## 第二部分：改进点与进展

项目改进分为两条并行实施轨道，均已完成 P0/P1/P2（Docker 实跑除外）。

### 轨道 A：门禁收口 · 三条断链 · 运行时链路 · 质量度量

> 源自 `下一步实施计划.md`。复核发现完整性文档的两项门禁结论已过期——实测各差一个修复即可转绿。

#### A-P0：门禁与核心断链 ✅

| 编号 | 任务 | 实现 |
|---|---|---|
| A-P0-1 | 修复后端测试门禁 | `VectorizationService.chunkDocument` overlap 死循环导致 OOM（保证 start 严格前进）；连带修好被 OOM 掩盖的 3 个向量测试缺陷（自增主键回填、正交向量数据）。实测原"92 failures/221 errors"实为门禁因 1 个 OOM 测试中断的假象 |
| A-P0-2 | 修复前端类型门禁 | `ScanVersionList.vue` 补 `del` 导入。实测原"大量 TS 错误"实为仅 1 处缺导入 |
| A-P0-3 | 接通业务图谱落库 | `FactController.extractDocFacts` 抽取业务事实后调用 `BusinessGraphBuilder.buildBusinessGraph` 落库，版本由最新扫描版本解析 |
| A-P0-4 | 修正 Fact 关联节点查询 | `getRelatedNodes` 改为 `Fact(projectId,sourcePath)→Evidence→NodeEvidence→GraphNode`（原 `evidenceId=factId` 口径错误，且 `Fact.evidenceIds` 未持久化） |

#### A-P1：闭环增强与技术债 ✅

| 编号 | 任务 | 实现 |
|---|---|---|
| A-P1-1 | PII 脱敏接入 LLM 网关 | `PiiMaskingService` 接入 `LlmGateway`，删除遗留 `.bak`，移除本地正则 |
| A-P1-2 | 解除测试排除并修绿 | 移除 POM 对 `agent/`/`builder/`/`llm/` 及单类的排除；修复暴露的真实问题：unmockable `lambdaQuery()` 链 → `LambdaQueryWrapper`、`doNothing` 误用、`VueRouteExtractor` 4 个 bug、MyBatis 外部 DTD 拉取、`FeatureMappingAgent` 自相矛盾断言 |
| A-P1-3 | 业务图谱集成测试 | `BusinessGraphBuilderTest` 端到端验证 domain+process+step → 节点/边/证据落库 |
| A-P1-4 | 向量端到端测试 | `VectorRetrievalServiceTest` 新增"分片→embedding 存储→语义检索"链路测试 |
| A-P1-5 | 隔离演示数据 | `RuntimeGraph.vue` 加"trace 未接入"提示；`CodeGraph` 误导注释修正；audit/数据血缘已是真实接口 |

#### A-P2：二期能力 ✅（Docker 实跑除外）

| 编号 | 任务 | 实现 |
|---|---|---|
| A-P2-1 | 运行时 trace ingestion | 新增 `RuntimeTrace` 实体 + `lg_runtime_trace` 表 + `RuntimeTraceRepository` + `TraceIngestionService`（span 持久化 + 服务拓扑聚合 + operationName 命中节点提升 verifiedScore）+ `TraceController`（上报/拓扑/列表）+ 前端 `trace.api.ts`，`RuntimeGraph.vue` 接真实接口（无数据回退近似并提示） |
| A-P2-2 | 抽取器增强 | `MyBatisXmlExtractor` 实现 `<include refid>` 展开 + 近似行号解析；`SqlTableExtractor` FromItem 改为明确限制说明；Vue 路由解析在 A-P1-2 已增强 |
| A-P2-3 | 图谱质量度量 | `GraphMetricsReport` + `generateGraphMetrics` + `ReportController` 端点 + 单测 |
| A-P2-4 | Docker 全栈验证 | ⚠️ 当前环境无 Docker 守护进程，仅静态核验 compose 配置（`docker compose config` 解析通过）。compose 已改为**仅构建前后端**，PG/Neo4j/Redis/MinIO 用外部服务器经 `.env` 注入；运行时验证待 Docker 宿主 |

#### A 补充收尾项 ✅

- **`TestCase.scenario` 字段**：补持久化字段 + init.sql/H2 schema 列，生成逻辑写入。
- **`ApiTestExecutor` LOGIN 前置条件**：实现登录取 token（可配 `loginPath`/`tokenField`/`contextKey` 等）写入上下文。
- **`FrontendGraphBuilder` 参数相似性打分**：路径参数个数匹配加分。
- **`CodeRepo.backend/frontendSubPath` 与 H2 schema 不一致**：补 schema 列，消除 `SourceControllerTest` 偶发 500。

### 轨道 B：图谱闭环 · 查询正确性 · 跨图谱映射

> 源自 `下一步优化方案.md`。核心问题不是"没有链路"，而是扫描/入库/同步/查询/确认/运行时验证之间的字段与过滤口径不闭合。

#### B-P0：修正图谱闭环和查询正确性 ✅

| 编号 | 任务 | 实现 |
|---|---|---|
| B-P0-1 | 统一版本过滤和查询入口 | `getApiCallChain`/`getTableImpact`/`getFeatureView`/`getBusinessView` 新增 `projectId` 参数 + Cypher 完整版本过滤；`module`/`domain` 为空时降级返回子图并标记 `moduleMissing`/`domainFallback` |
| B-P0-2 | 调整 Neo4j 同步策略 | `DELETE`→`DETACH DELETE`；同步范围 `CONFIRMED`→`CONFIRMED + PENDING_CONFIRM`；节点投影补 `sourceType`/`sourcePath`/`properties`，边补 `status`/`evidenceIds`；唯一约束改为内部 `id` |
| B-P0-3 | 补齐文档发现到解析闭环 | `discoverDocuments` 新增 `versionId` 写入；`parseDocument` 对缺失 `filePath` 给出明确错误而非 NPE |
| B-P0-4 | 修正 Fact 来源和证据模型 | `saveFact` 新增 `sourceType` 参数：Controller/Service→`CODE_AST`、Mapper XML→`MAPPER_XML`、前端→`FRONTEND_AST` |

#### B-P1：打通业务/代码/运行时图谱 ✅

| 编号 | 任务 | 实现 |
|---|---|---|
| B-P1-1 | 自动触发业务功能到代码映射 | `extractDocFacts` 构建业务图谱后立即调用 `mapFeaturesToCode(projectId, versionId)` 触发 Feature→Page/API 映射 |
| B-P1-2 | 替换业务域轮询兜底 | 移除 `domainIndex % domainCount` 轮询分配；仅当 LLM 输出有明确 domain 时建确定边，否则流程节点保持孤立 + `PENDING_CONFIRM` |
| B-P1-3 | 持久化运行时验证结果 | `GraphNode` 新增 `runtimeVerified`/`lastSeenAt`/`traceCount` 持久化字段；`markRuntimeVerified` 按 spanKind 精确匹配节点类型（SERVER/CLIENT→ApiEndpoint，INTERNAL→Service/Repository/Mapper，SQL→Mapper/Table），含 PostgreSQL migration `V1.3__runtime_verification.sql` + H2 schema |
| B-P1-4 | 增强服务调用绑定准确率 | `ServiceCallExtractor` 重构 `collectInjectedVarTypes`（变量名→类型），通过 scope 解析精确匹配调用目标，记录 sourcePath/lineNumber |

#### B-P2：扫描可控性 · 前端可用性 · 质量保障 ✅

| 编号 | 任务 | 实现 |
|---|---|---|
| B-P2-1 | 扫描范围配置生效 | `startFullScan` 读取 `backendSubPath`/`frontendSubPath`，后端/前端扫描分别限定根目录，日志输出实际子路径 |
| B-P2-2 | 后端状态感知查询 | `getUnifiedGraph` 新增 `statusFilter` 参数；节点返回 `sourceType`/`verifiedScore`/`runtimeVerified`/`lastSeenAt`/`traceCount`，边返回 `status`；空视图返回结构化 `emptyReasons` |
| B-P2-3 | 补齐测试 | `BusinessGraphBuilderTest` 边数断言更新（移除轮询边后 3→2）；新增 migration + H2 schema 列；379 测试全绿 |

#### 轨道 B 全量变更清单

| 文件 | B-P0 | B-P1 | B-P2 | 改动 |
|---|:--:|:--:|:--:|---|
| `GraphQueryService.java` | ✅ | - | ✅ | 版本过滤 + fallback + statusFilter + 运行时字段 |
| `GraphQueryController.java` | ✅ | - | ✅ | projectId 传递 + statusFilter 参数 |
| `Neo4jSyncService.java` | ✅ | - | - | DETACH DELETE + PENDING_CONFIRM + 属性投影 + id 约束 |
| `ProjectScanner.java` | ✅ | - | ✅ | discoverDocuments+versionId + saveFact+sourceType + 子路径生效 |
| `SourceController.java` | ✅ | - | - | parseDocument null 检查 |
| `FactController.java` | - | ✅ | - | mapFeaturesToCode 自动触发 |
| `BusinessGraphBuilder.java` | - | ✅ | - | 移除轮询域分配 |
| `TraceIngestionService.java` | - | ✅ | - | spanKind 精确匹配 + 新字段持久化 |
| `GraphNode.java` | - | ✅ | - | runtimeVerified/lastSeenAt/traceCount |
| `ServiceCallExtractor.java` | - | ✅ | - | 变量名→类型映射 + 行号追踪 |
| `schema-h2.sql` / `V1.3__runtime_verification.sql` | - | ✅ | - | 新增字段 / PostgreSQL migration |

---

## 第三部分：剩余缺口与下一步

### 3.1 唯一未实跑项

| 项 | 状态 | 处理建议 |
|---|---|---|
| Docker 全栈验证 | ⚠️ 环境无 Docker 守护进程 | compose 已改为仅构建前后端，外部依赖经 `deploy/.env` 注入。在 Docker 宿主上 `cp .env.example .env` 填好外部连接后执行 `docker compose up --build -d`；首次需手动在外部 PostgreSQL 执行 `docs/sql/init.sql`（含 `lg_runtime_trace`），再验证健康检查、扫描、查询、LLM/向量全链路 |

### 3.2 待真实数据验证的能力

- **运行时链路**：后端 trace ingestion + 拓扑聚合 + 运行时验证打分已落地，需真实 span 上报（OpenTelemetry/日志采样）后验证端到端效果。
- **跨图谱映射**：`mapFeaturesToCode` 已接通，需在真实项目上验证 Feature→Page/API/Service/Mapper/Table 的匹配准确率。
- **图谱质量度量**：`GraphMetricsReport` 五维指标已实现，需在真实图谱数据上观察分布合理性。

### 3.3 可后置的优化方向

> 以下不应在基础闭环稳定前扩大复杂度。

1. **性能优化**：图谱大数据量渲染（WebWorker、聚合节点）、首屏加载、代码分割。
2. **功能增强**：图谱路径分析/影响分析、邻居展开收起、图计算算法集成。
3. **抽取精度**：服务调用链对多 Mapper/多 Service 的精确绑定、动态 SQL 的"可能读写"低置信关系。
4. **测试覆盖**：前端单测环境完善（Element Plus/router/axios stub）、E2E 与 Vitest 隔离、后端 Testcontainers。

### 3.4 不建议优先投入

- 新增更多图谱类型而不解决现有三类图谱间的映射。
- 直接重写全部 extractor，而非先修正版本/状态/证据/同步口径。
- 把所有 `PENDING_CONFIRM` 自动改为 `CONFIRMED`（看似完整，实降可信度）。
- 在查询正确性和数据闭环稳定前做大规模性能优化。

---

## 第四部分：演进历史

> 记录项目从"不能构建"到"核心闭环收口"的关键节点，便于理解结论变化。早期文档的乐观结论（如"100% 完成/可上线"）均已被后续核验更正。

### 阶段一（2026-06-27）：恢复基础运行门槛

源自 `项目整体缺口审计报告.md`。彼时项目从"前后端都不能构建"推进到"基础构建/打包可通过，Compose 可解析"：

- 移除无效测试依赖 `spring-boot-webmvc-test`，恢复后端编译/打包。
- 修复前端真实构建错误（缺失类型导入、API barrel 重复导出、SFC 错误等），恢复 `npm run build`。
- 修复 `ReportingService.saveReport` 递归风险、最小接口路径（test-run/数据库连接/文档上传）、启动配置。
- 识别出 P0 级缺口：数据库脚本与实体字段不一致、前端类型检查未确认、测试未通过、演示数据/随机结果/TODO 散布。

### 阶段二（早期）：功能清单铺开

源自 `未完成功能清单.md`。完成大量 P0-P3 功能模块（JWT 安全、参数校验、向量检索、报告生成、6 个 Agent、异步任务、前端图谱组件、i18n、PWA 等），但该文档"100% 完成"的结论被阶段一审计明确标记为过度乐观、不可作为交付依据。

### 阶段三（2026-06-29）：完整性核验

源自 `项目整体完整性检查与缺失总结.md`。以代码/路由/测试结果为准重新核验，判定"核心模块覆盖较全但集成验证未收口"，定位三条断链（业务图谱未落库、Fact 证据口径、向量路径）与运行时链路缺失。

### 阶段四（2026-06-29/30）：两条轨道实施收口

轨道 A 与轨道 B 并行实施完成（见第二部分）。最终门禁：后端 379 tests 全绿、前端 type-check + build 通过。三条断链接通，运行时链路后端落地，图谱查询版本隔离与状态感知完成。

### 关键结论更正记录

| 早期结论 | 出处 | 更正后事实 |
|---|---|---|
| "项目整体完成度 100%/可上线运行" | 未完成功能清单 | 早期过度乐观；以门禁与集成验证为准 |
| 后端 `mvn test` 92 failures/221 errors | 完整性检查（实施前） | 实为门禁因 1 个 OOM 测试中断；现 379 tests 全绿 |
| 前端大量 TS 错误 | 完整性检查（实施前） | 实为仅 1 处 `del` 未导入；现 type-check 通过 |
| 向量前后端路径不一致 | 完整性检查（实施前） | 复核为早已统一 `/lg/vector/projects/{projectId}` |
| 业务图谱 `buildBusinessGraph` 无调用者 | 完整性检查（实施前） | 已在 `FactController.extractDocFacts` 接通并触发 `mapFeaturesToCode` |
| 运行链路无后端 trace ingestion | 完整性检查（实施前） | 已落地 `TraceIngestionService`/`TraceController`/`lg_runtime_trace` |
| 批量测试生成未完整 | 完整性检查（实施前） | 复核为已实现（按 scope.nodeTypes 批量生成落库） |
