# LegacyGraph 下一步执行计划

> 基于 `doc/LegacyGraph 项目整体完整性检查与缺失总结.md`（2026-06-29）
> 当前状态：**后端测试门禁失败 + 前端类型检查失败 + 3条核心断链**

---

## ✅ 总体策略：先修门禁，再补断链，后增强

执行顺序：
1. **P0：修复后端测试门禁** — 让 `mvn test` 通过或接近通过
2. **P0：修复前端类型门禁** — 让 `npm run type-check && npm run build` 通过
3. **P0：接通业务图谱自动落库** — 打通业务事实→业务图谱闭环
4. **P0：统一向量 API 路径** — 修复前后端接口不一致
5. **P0：修正事实-证据关联查询** — 统一关联口径
6. **P1：增强核心链路** — 脱敏接入、演示数据隔离、集成测试
7. **P2：二期能力** — 运行时 trace、抽取器增强、Docker 验证

---

## P0-1：修复后端测试门禁

**目标**：`cd backend && mvn test` 通过
**当前**：46 个 surefire 报告，359 tests，92 failures，221 errors

### 步骤

| # | 任务 | 说明 | 预估 |
|---|------|------|------|
| 1.1 | 执行 `mvn test -q` 获取当前失败详情 | 收集完整失败报告 | 5min |
| 1.2 | 分类错误类型 | 分析是配置问题 / 依赖问题 / 代码问题 / 测试本身问题 | 15min |
| 1.3 | 修复 pom.xml 测试排除配置 | 确认 `**/agent/**`、`**/builder/**`、`**/llm/**` 等排除是否合理 | 10min |
| 1.4 | 批量修复编译/依赖错误 | 修复导致大面积报错的根因（配置缺失、Bean 注入失败等） | 1-2h |
| 1.5 | 逐模块修复测试失败 | 按模块逐步修，先修核心模块（Service/Controller/Entity），再修集成测试 | 2-3h |
| 1.6 | 执行 `mvn test` 并确认门禁通过 | 或确认剩余失败数量可控 | 5min |

### 关键风险
- 大量测试使用 Spring Context 加载，可能因为配置/环境缺失大面积报错
- 部分测试依赖 Neo4j/PostgreSQL 等外部服务

---

## P0-2：修复前端类型门禁

**目标**：`cd frontend && npm run type-check && npm run build` 通过
**当前**：大量 TypeScript 错误，覆盖 chart、graph、stores、locales、request 等模块

### 步骤

| # | 任务 | 说明 | 预估 |
|---|------|------|------|
| 2.1 | 执行 `npm run type-check` 收集错误 | 先 `npm install`，再收集全部错误 | 5min |
| 2.2 | 按模块/模式对错误分组 | 如：AxiosResponse 解包类型 / Vue Flow 类型 / 缺失导入 / 接口不一致 | 15min |
| 2.3 | 修复 request 解包类型错误 | `get<T>` 和 `post<T>` 返回 `res.data` 的业务类型，页面/组件拿到的类型需一致 | 30min |
| 2.4 | 修复 Vue Flow / @vue-flow 类型错误 | 升级或锁定类型定义 | 30min |
| 2.5 | 修复缺失的 Vue 导入（ref/computed/watch...) | 批量补全 | 15min |
| 2.6 | 修复 i18n locale 类型 | 确保 `locales/*.ts` 类型兼容 | 15min |
| 2.7 | 修复图谱组件类型错误 | GraphViewer、BusinessGraph、FeatureGraph 等 | 30min |
| 2.8 | 修复 store/API 调用类型错误 | 确保各页面调用 API 返回类型匹配 | 30min |
| 2.9 | 执行 `npm run type-check` 和 `npm run build` 确认通过 | 最终验证 | 5min |

---

## P0-3：接通业务事实→业务图谱自动落库

**目标**：在文档解析或扫描完成时自动调用 `BusinessGraphBuilder.buildBusinessGraph(projectId, versionId, facts)`

### 步骤

| # | 任务 | 说明 | 预估 |
|---|------|------|------|
| 3.1 | 确定调用入口 | 在 `ProjectScanner.startFullScan` 完成后，或在 `FactController.extractDocFacts` 返回后调用 Builder | 15min |
| 3.2 | 在扫描主流程中增加业务图谱构建步骤 | 扫描完成 → 获取文档内容 → `DocUnderstandingAgent.extractBusinessFacts` → `BusinessGraphBuilder.buildBusinessGraph` | 30min |
| 3.3 | 补集成测试 | 验证文档事实抽取 → 业务图谱节点/边 → 证据关联 → 业务视图查询 | 30min |

### 关键风险
- 需要 LLM 可用（当前使用火山引擎，需确认 API Key 有效）
- BusinessFactExtraction 的数据结构需与 Builder 完全一致

---

## P0-4：统一向量 API 路径

**目标**：前端 `vector.api.ts` 与后端 `VectorController` 路径一致

### 现状
- **后端**：`/api/projects/{projectId}/vector/...`
- **前端调用**：`/lg/vector/projects/{projectId}/...`（不存在）

### 步骤

| # | 任务 | 预估 |
|---|------|------|
| 4.1 | 确认前端 `vector.api.ts` 中的实际路径 | 5min |
| 4.2 | 修正其中一个，使前后端一致 | 推荐改前端为后端路径 | 10min |
| 4.3 | 补向量端到端联调测试 | 15min |

---

## P0-5：修正事实-证据关联查询

**目标**：`FactController.getRelatedNodes` 能正确查到关联节点

### 现状
- `FactController.getRelatedNodes` 用 `id` 查询 `NodeEvidence` 的 `evidenceId`
- 实际上应该按 `Fact.evidenceIds` 查证据列表，再通过 `NodeEvidence` 查节点
- 或者事实的 ID 本身就是证据 ID？

### 步骤

| # | 任务 | 预估 |
|---|------|------|
| 5.1 | 厘清数据模型：`Fact.evidenceIds` 与 `NodeEvidence` 的关系 | 15min |
| 5.2 | 根据结论修正 `getRelatedNodes` 逻辑 | 15min |
| 5.3 | 编写单元测试验证 | 15min |

---

## P1：核心闭环增强

| # | 任务 | 前置条件 | 预估 |
|---|------|----------|------|
| P1-1 | `PiiMaskingService` 接入 `LlmGateway` | P0-1 通过 | 20min |
| P1-2 | 清理前端 API 类型和 request 解包模型 | P0-2 中同步完成 | - |
| P1-3 | 修复图谱组件与 Vue Flow/G6 类型适配 | P0-2 中同步完成 | - |
| P1-4 | 移除或隔离演示数据（CodeGraph 示例、DataLineage 兜底、audit mock） | P0-1, P0-2 通过 | 30min |
| P1-5 | 补业务图谱集成测试 | P0-3 完成后 | 30min |
| P1-6 | 补向量端到端测试 | P0-4 完成后 | 20min |
| P1-7 | 排除不可修复的单测或标为 @Disabled | P0-1 过程中 | 15min |

---

## P2：二期能力

| # | 任务 | 前置条件 | 预估 |
|---|------|----------|------|
| P2-1 | 运行时 trace ingestion（OpenTelemetry / trace graph） | P0 全部完成 | 2-3h |
| P2-2 | 抽取器增强（Vue AST 解析、MyBatis include 补齐等） | P0 全部完成 | 1-2h |
| P2-3 | 图谱质量度量体系 | P1 全部完成 | 1-2h |
| P2-4 | Docker 全栈部署验证 | P0, P1 全部完成 | 1h |

---

## 执行顺序甘特图

```
阶段        | 任务              | 优先级 | 依赖     | 预估
------------|-------------------|--------|----------|------
Phase 1     | 后端测试门禁修复   | P0     | -        | 3-5h
Phase 1     | 前端类型门禁修复   | P0     | -        | 2-3h
Phase 2     | 业务图谱自动落库   | P0     | Phase 1  | 1h
Phase 2     | 向量API路径统一    | P0     | Phase 1  | 30min
Phase 2     | 事实-证据关联修正  | P0     | Phase 1  | 30min
Phase 3     | P1 增强           | P1     | Phase 2  | 2-3h
Phase 4     | P2 二期能力       | P2     | Phase 3  | 5-8h
```

**建议立即开始的 3 件事：**

1. **终端 1**：`cd backend && mvn test -q` 收集失败详情
2. **终端 2**：`cd frontend && npm install && npm run type-check 2>&1 | head -200` 收集类型错误
3. 等待结果，分类分析后再分段修复
