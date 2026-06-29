# LegacyGraph 下一步优化方案

日期：2026-06-29

本文基于当前代码现状整理下一步优化方向。现有 `doc/LegacyGraph 下一步实施计划.md` 中有部分“已完成”结论与当前源码仍存在差异，因此后续执行应以源码和可验证测试结果为准。

## 1. 当前实现判断

LegacyGraph 目前已经具备三类图谱的基本获取链路：

- 代码图谱：`SourceController.scanRepo` 创建版本后调用 `ProjectScanner.startFullScan`，扫描 Controller、Service、Mapper XML、Vue/React 页面，并通过各类 builder 写入 `lg_graph_node`、`lg_graph_edge`。
- 业务图谱：`FactController.extractDocFacts` 调用文档理解 Agent，再由 `BusinessGraphBuilder.buildBusinessGraph` 写入业务域、流程、对象、规则、角色、功能等节点。
- 运行时图谱：`TraceIngestionService.ingest` 接收 trace span，写入 `lg_runtime_trace`，并尝试标记运行时验证结果。

图谱最终有两条查询路径：

- PostgreSQL 路径：`GraphQueryService.getUnifiedGraph` 直接查询 `lg_graph_node`、`lg_graph_edge`。
- Neo4j 路径：`Neo4jSyncService.syncGraph` 同步节点边后，`GraphQueryService` 查询 API 调用链、表影响、功能视图、业务视图。

当前主要问题不是“没有链路”，而是链路之间的口径没有闭合：扫描、入库、同步、查询、确认状态、运行时验证使用的字段和过滤条件不完全一致，导致部分图谱已经写入 PostgreSQL，但在 Neo4j 或前端专题视图里查不到。

## 2. 关键问题

### 2.1 查询口径与写入口径不一致

`GraphQueryService.getApiCallChain` 和 `getTableImpact` 接收 `versionId`，但当前 Cypher 没有按版本过滤。只要同一项目或不同版本存在同名 API、表、方法，就可能串到旧版本或其他版本的数据。

`getFeatureView` 使用 `n.module = $module` 过滤，但当前扫描和 builder 并没有稳定写入 `module`。`getBusinessView` 使用 `n.businessDomain` 或 `n.domain` 过滤，但 `BusinessGraphBuilder` 当前没有把这些属性稳定写入节点属性。结果是专题查询天然容易为空。

### 2.2 Neo4j 同步范围过窄

`Neo4jSyncService.syncGraph` 当前只同步 `status = CONFIRMED` 的节点和边。业务文档抽取出来的节点默认是 `PENDING_CONFIRM`，所以业务图谱即使已经进入 PostgreSQL，也不会进入 Neo4j。另一方面，同步到 Neo4j 的属性只包含基础字段，`sourceType`、`sourcePath`、`properties`、`module`、`domain` 等查询和展示所需字段没有完整投影。

### 2.3 文档发现与文档解析没有闭环

`ProjectScanner.discoverDocuments` 会发现 Markdown、PDF、docx、txt 等文件并写入 `lg_document`，但当前发现逻辑没有设置 `filePath`。后续 `SourceController.parseDocument` 依赖 `doc.filePath` 打开文件，因此自动发现的文档无法稳定进入解析和业务图谱构建链路。

上传文档路径是 `${java.io.tmpdir}/legacygraph/uploads/{projectId}/{uuid}_{fileName}`，这条链路有 `filePath`，但仓库内自动发现的文档链路缺少同等信息。

### 2.4 Fact 来源类型和证据口径不准

`ProjectScanner.saveFact` 当前统一写 `sourceType = CODE_AST`。Mapper XML、前端页面、文档、数据库结构都不应该被标记成同一种来源。后续如果要做可信度、证据解释、冲突解决，这个字段会直接影响判断。

业务图谱会创建 evidence，但当 `sourcePath` 为空时仍可能创建不完整证据。代码图谱、文档图谱、运行时图谱之间缺少统一的 evidence 结构和可追溯展示。

### 2.5 文档业务图谱与代码图谱没有自动映射闭环

`BusinessGraphBuilder.mapFeaturesToCode` 已经有“功能 -> 页面/API”的简单匹配逻辑，但当前 `FactController.extractDocFacts` 构建业务图谱后没有触发这一步。实际效果是业务节点和代码节点通常分离存在，无法形成“业务功能由哪些页面/API实现”的闭环。

此外，`BusinessGraphBuilder` 当前把流程分配到业务域时存在轮询式兜底，容易生成看似完整但语义不可靠的边。后续应避免把低置信关系直接伪装成确定关系。

### 2.6 运行时验证状态可能没有持久化

`TraceIngestionService.markRuntimeVerified` 会尝试设置 `GraphNode.verifiedScore`，但 `GraphNode` 中部分 LLM/验证相关字段是 `@TableField(exist = false)`。如果数据库没有对应列，这类验证结果只在对象内临时存在，不会可靠落库。

同时，trace 的 `operationName` 与图节点 `nodeKey` 的匹配规则需要规范化。否则运行时 span 已经进入库，但无法准确反向标记 API、Service、Mapper 节点。

### 2.7 扫描范围与项目配置没有完全生效

`ProjectScanner.discoverSubPaths` 会识别 `backendSubPath`、`frontendSubPath`，项目也保存了 include/exclude 配置，但 `startFullScan` 后续扫描仍主要围绕 `baseDir` 展开。大型仓库、多模块仓库、前后端混合仓库会出现扫描过宽、耗时偏高、误识别增多的问题。

### 2.8 服务调用抽取仍偏轻量

`ServiceCallExtractor` 当前更像静态语法级别抽取，目标类、注入变量、Controller 方法到 Service 方法、Service 到 Mapper 方法之间的准确绑定仍需要增强。否则 API 调用链只能做到“有一些边”，难以做到可解释、可定位、可验证。

## 3. 优化目标

下一阶段不建议继续横向堆新图谱类型，优先把现有三类图谱打通：

1. 同一版本内，扫描到的节点和边能在 PostgreSQL 与 Neo4j 查询中保持一致。
2. 文档业务节点、代码节点、数据库节点、运行时节点之间能建立可解释映射。
3. 查询结果不串版本、不依赖未写入字段、不因为 `PENDING_CONFIRM` 状态直接消失。
4. 每个图谱节点都能回答“来自哪里、为什么可信、是否被运行时验证过”。
5. 大型项目扫描可控、可复跑、可定位失败步骤。

## 4. P0：先修正图谱闭环和查询正确性

### 4.1 统一版本过滤和查询入口

涉及文件：

- `backend/src/main/java/io/github/legacygraph/service/GraphQueryService.java`
- `backend/src/main/java/io/github/legacygraph/service/Neo4jSyncService.java`

改动建议：

- `getApiCallChain` 的 Cypher 增加 `projectId`、`versionId` 过滤，路径上的节点也要限定同一版本。
- `getTableImpact` 增加 `projectId`、`versionId` 过滤，避免同名表跨版本串联。
- `getFeatureView` 不应直接依赖当前没有稳定写入的 `module`。短期可改为：
  - 有 `module` 时按 module 过滤；
  - 无 `module` 时返回该版本的 Feature、Page、ApiEndpoint、Service、Repository 子图；
  - 同时在结果里标记 `moduleMissing = true`，提示后续补齐。
- `getBusinessView` 不应只依赖 `businessDomain/domain` 属性。短期可从 `BusinessDomain` 节点名、`BELONGS_TO`、`PART_OF`、`IMPLEMENTS` 等边扩展子图；长期再补稳定 domain 属性。

验收标准：

- 同一项目创建两个版本，同名 API 和表不会互相串联。
- 未写入 `module` 的项目，功能视图不再直接空白。
- 业务文档抽取后，即使节点仍是 `PENDING_CONFIRM`，也能在明确标注状态的视图中看到。

### 4.2 调整 Neo4j 同步策略

涉及文件：

- `backend/src/main/java/io/github/legacygraph/service/Neo4jSyncService.java`
- `backend/src/main/java/io/github/legacygraph/entity/GraphNode.java`
- `backend/src/main/java/io/github/legacygraph/entity/GraphEdge.java`

改动建议：

- 删除旧图时使用 `DETACH DELETE`，避免已有关系导致删除失败。
- Neo4j 唯一约束不要只依赖 `nodeKey`。至少应使用内部 `id` 做唯一键，或使用 `projectId + versionId + nodeKey` 组合口径。
- 同步节点时投影更多属性：`sourceType`、`sourcePath`、`sourceRef`、`properties`、`confidence`、`status`。
- 同步边时投影 `edgeType`、`confidence`、`status`、`evidenceId` 或 evidence 引用。
- 支持按视图需要同步 `PENDING_CONFIRM` 节点，但查询和前端展示必须清楚标记状态，不能当作确认事实。

验收标准：

- `CONFIRMED` 与 `PENDING_CONFIRM` 在查询结果中状态清晰。
- Neo4j 与 PostgreSQL 对同一 `projectId/versionId` 的节点数量差异有可解释规则。
- 重复同步同一版本不会因约束或残留关系失败。

### 4.3 补齐文档发现到解析的闭环

涉及文件：

- `backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java`
- `backend/src/main/java/io/github/legacygraph/controller/SourceController.java`
- `backend/src/main/java/io/github/legacygraph/entity/Document.java`

改动建议：

- `discoverDocuments` 写入 `filePath`，建议保存仓库内相对路径和绝对路径中的一种主路径，并补充 `repoId`、`versionId`。
- 自动发现文档后，增加可选解析步骤：
  - 只发现，不解析；
  - 发现并解析；
  - 发现、解析并抽取业务事实。
- `parseDocument` 对缺失 `filePath` 给出明确错误，而不是进入不可解释失败。
- 对仓库文档和上传文档统一 `Document` 字段口径，避免上传链路和扫描链路分裂。

验收标准：

- 仓库中的 Markdown 文档经过一次 scan 后，可以直接在后台触发解析。
- `lg_document.file_path` 对自动发现文档非空。
- 文档解析生成的 chunk 能关联到正确 `projectId/versionId/documentId`。

### 4.4 修正 Fact 来源和证据模型

涉及文件：

- `backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java`
- `backend/src/main/java/io/github/legacygraph/builder/*GraphBuilder.java`
- `backend/src/main/java/io/github/legacygraph/entity/Fact.java`
- `backend/src/main/java/io/github/legacygraph/entity/Evidence.java`

改动建议：

- `saveFact` 增加 `sourceType` 参数，不再统一写 `CODE_AST`。
- Controller/Service/Java AST 使用 `CODE_AST`。
- Mapper XML 使用 `MAPPER_XML` 或等价枚举。
- 前端页面使用 `FRONTEND_AST`。
- 数据库结构使用 `DB_SCHEMA`。
- 文档抽取使用 `DOC_AI`。
- trace 使用 `RUNTIME_TRACE`。
- 证据必须至少包含 `sourceType`、`sourcePath/sourceRef`、`extractor`、`confidence`、`lineStart/lineEnd` 或 chunk id。

验收标准：

- 任意一个图节点能追溯到对应 fact/evidence。
- Mapper 和前端 fact 不再显示为 `CODE_AST`。
- 低置信或缺证据的边不会默认进入 `CONFIRMED`。

## 5. P1：打通业务图谱、代码图谱和运行时图谱

### 5.1 自动触发业务功能到代码实现的映射

涉及文件：

- `backend/src/main/java/io/github/legacygraph/controller/FactController.java`
- `backend/src/main/java/io/github/legacygraph/builder/BusinessGraphBuilder.java`

改动建议：

- `extractDocFacts` 调用 `buildBusinessGraph` 后，触发 `mapFeaturesToCode`。
- 如果代码图谱还未扫描完成，应记录待映射任务，而不是静默跳过。
- 增加 `GraphMappingService` 或等价编排类，统一处理：
  - Feature -> Page
  - Feature -> ApiEndpoint
  - ApiEndpoint -> Service
  - Service -> Mapper
  - Mapper -> Table
- 匹配结果低于阈值时写 `PENDING_CONFIRM`，并保留候选解释。

验收标准：

- 文档抽取出 Feature 后，能看到 Feature 到 Page/API 的候选关系。
- 没有匹配上的 Feature 会进入“待确认/待补证据”列表。
- 映射逻辑有单元测试覆盖相似名称、无匹配、多个候选三种情况。

### 5.2 替换业务域轮询兜底

涉及文件：

- `backend/src/main/java/io/github/legacygraph/builder/BusinessGraphBuilder.java`

改动建议：

- 不再把流程轮询分配到业务域。
- LLM 输出中有明确 domain/process 关系时才建确定边。
- 没有明确关系时：
  - 建候选边，状态为 `PENDING_CONFIRM`；
  - 或只建孤立流程节点，等待用户确认。
- 在 evidence 中记录映射依据，例如文档 chunk、关键词、LLM reasoning 摘要。

验收标准：

- 同一文档多业务域场景下，不会因为数组顺序改变而生成不同业务归属。
- 低置信关系不会显示为已确认事实。

### 5.3 持久化运行时验证结果

涉及文件：

- `backend/src/main/java/io/github/legacygraph/service/TraceIngestionService.java`
- `backend/src/main/java/io/github/legacygraph/entity/GraphNode.java`
- 数据库迁移脚本

改动建议：

- 为运行时验证增加明确落库字段，例如 `runtime_verified`、`verified_score`、`last_seen_at`、`trace_count`。
- 移除或调整 `@TableField(exist = false)`，确保需要持久化的字段确实有表字段。
- 规范 trace operation 到 graph node 的匹配：
  - HTTP span 匹配 `ApiEndpoint`；
  - 方法 span 匹配 `Service/Repository/Mapper`；
  - SQL span 匹配 `Mapper/Table`。
- 对无法匹配的 trace 记录 unmatched 原因，供后续规则优化。

验收标准：

- 运行时 span 进入后，对应图节点能持久显示验证分数和最后命中时间。
- 重启服务后验证状态不丢失。
- unmatched trace 可查询、可统计。

### 5.4 增强服务调用和 SQL 绑定准确率

涉及文件：

- `backend/src/main/java/io/github/legacygraph/extractor/ServiceCallExtractor.java`
- `backend/src/main/java/io/github/legacygraph/extractor/JavaControllerExtractor.java`
- `backend/src/main/java/io/github/legacygraph/extractor/MyBatisExtractor.java`

改动建议：

- 解析 Spring 注入字段和构造器注入，建立变量名到类型的映射。
- Controller 方法内调用 Service 时，记录调用方法名和参数线索。
- Service 调 Mapper 时，绑定 Mapper 接口方法和 XML statement id。
- Mapper XML 的表名抽取需要保留 SQL 类型：SELECT、INSERT、UPDATE、DELETE。
- 对动态 SQL 建立“可能读写”关系，并降低 confidence。

验收标准：

- API 调用链能稳定输出 `ApiEndpoint -> Controller -> Service -> Mapper -> Table`。
- 多 Mapper、多 Service 场景不会只靠文件名猜测。
- SQL 动态片段不会被误标成高置信确定关系。

## 6. P2：提升扫描可控性、前端可用性和质量保障

### 6.1 让扫描范围配置真正生效

涉及文件：

- `backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java`
- `backend/src/main/java/io/github/legacygraph/entity/Project.java`

改动建议：

- `startFullScan` 使用 `backendSubPath`、`frontendSubPath` 分别限定扫描根目录。
- include/exclude 配置应用到代码、文档、Mapper、前端扫描的所有 walk 过程。
- 默认排除：`.git`、`target`、`build`、`dist`、`node_modules`、`.idea`、`.vscode`、日志目录和临时目录。
- 对每一步记录 scanned file count、skipped file count、duration、error count。

验收标准：

- 大型多模块仓库可以只扫指定后端或前端子目录。
- 扫描日志能明确说明哪些目录被排除。
- 单步失败不会让用户无法判断失败位置。

### 6.2 前端视图改为状态感知

涉及文件：

- `frontend` 图谱展示相关组件
- `backend/src/main/java/io/github/legacygraph/service/GraphQueryService.java`

改动建议：

- 节点按状态展示：已确认、待确认、低置信、运行时验证。
- 专题视图为空时返回结构化原因：
  - 没有扫描数据；
  - 有数据但未同步 Neo4j；
  - 有数据但缺少 module/domain 属性；
  - 过滤条件过窄。
- 图谱详情面板展示 evidence、sourcePath、confidence、status、lastSeenAt。

验收标准：

- 用户能从空图页面判断下一步该扫描、同步、确认还是调整过滤条件。
- 同一个节点在统一图、功能视图、业务视图中展示状态一致。

### 6.3 补齐测试矩阵

建议新增或补强测试：

- `ProjectScanner`：
  - 自动发现文档时写入 `filePath/versionId`。
  - 不同来源 fact 写入不同 `sourceType`。
  - include/exclude 对各类扫描生效。
- `GraphQueryService`：
  - API 调用链和表影响必须带版本过滤。
  - 缺少 module/domain 时有合理 fallback。
- `Neo4jSyncService`：
  - 重复同步同一版本不失败。
  - `PENDING_CONFIRM` 节点同步策略符合预期。
  - 节点属性完整投影。
- `BusinessGraphBuilder`：
  - 不再轮询分配业务域。
  - Feature 到 Page/API 映射可解释。
- `TraceIngestionService`：
  - trace 命中后验证状态可持久化。
  - unmatched trace 可统计。

基础验证命令建议：

```bash
cd backend
rtk mvn test
```

```bash
cd frontend
rtk npm run type-check
rtk npm run build
```

## 7. 建议执行顺序

第一阶段先做 P0，目标是“已有数据能查准、查全、可解释”：

1. 修正 `GraphQueryService` 版本过滤和专题视图 fallback。
2. 调整 `Neo4jSyncService` 同步策略和属性投影。
3. 补齐 `discoverDocuments` 的 `filePath/versionId`。
4. 修正 `saveFact` 来源类型。

第二阶段做 P1，目标是“三类图谱真正连起来”：

1. `extractDocFacts` 后触发 Feature 到代码实现映射。
2. 替换业务域轮询兜底。
3. 持久化运行时验证结果。
4. 增强 Controller/Service/Mapper/Table 调用链准确率。

第三阶段做 P2，目标是“可规模化、可排障、可交付”：

1. 扫描范围配置生效。
2. 前端视图状态感知。
3. 测试矩阵和样例项目回归。
4. 扫描指标和图谱质量报告。

## 8. 不建议优先投入的事项

以下事项可以后置，避免在基础闭环未稳定时扩大复杂度：

- 新增更多图谱类型，但不解决当前三类图谱之间的映射。
- 直接重写全部 extractor，而不是先修正版本、状态、证据、同步口径。
- 把所有 `PENDING_CONFIRM` 自动改成 `CONFIRMED`。这会让图谱看起来完整，但会降低可信度。
- 先做大规模性能优化。当前更核心的问题是查询正确性和数据闭环。

## 9. 阶段性交付物

P0 完成后应交付：

- 版本隔离正确的 API 调用链和表影响查询。
- PostgreSQL 与 Neo4j 数据同步口径说明。
- 自动发现文档可解析。
- fact/evidence 来源类型修正。
- 对应单元测试或集成测试。

P1 完成后应交付：

- 业务 Feature 到页面/API/服务/表的端到端路径。
- 运行时验证结果持久化。
- 低置信候选关系确认机制。
- 调用链准确率样例报告。

P2 完成后应交付：

- 大型项目扫描配置和排除规则。
- 前端空图原因和节点证据展示。
- 样例项目回归报告。
- 图谱质量指标：节点数、边数、确认率、低置信率、运行时验证率、未匹配 trace 数。

## 10. 推荐的下一步落地范围

建议下一次迭代只锁定 P0，不要混入 P1/P2 的大改。最小闭环如下：

1. `GraphQueryService` 修版本过滤和 fallback。
2. `Neo4jSyncService` 修同步范围、属性投影和重复同步。
3. `ProjectScanner.discoverDocuments` 写入 `filePath/versionId`。
4. `ProjectScanner.saveFact` 改成按来源写 `sourceType`。
5. 为以上四项补测试。

这组改动完成后，才能可靠判断后续业务映射和运行时验证的真实效果。
