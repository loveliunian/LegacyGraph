# LegacyGraph 未完成功能清单

## 📋 概述

本文档记录 LegacyGraph 项目中，根据详细设计文档应该实现但尚未完成或部分完成的功能。

**项目整体完成度：约 55%**

| 模块 | 完成度 | 优先级 |
|------|--------|--------|
| 后端核心功能 | 65% | 🔴 高 |
| 前端界面功能 | 45% | 🔴 高 |
| 图谱展示与交互 | 30% | 🔴 高 |
| 测试验证闭环 | 50% | 🟠 中 |
| 报告与统计 | 10% | 🟠 中 |
| 权限控制 | 20% | 🟡 低 |

---

## 🔴 后端未完成功能

### 1. 向量检索服务 (P0)

**状态**：❌ 完全缺失

**设计文档要求**：
- 支持代码片段、文档分片的向量化存储
- 支持语义相似度检索
- 支持按项目和类型过滤的向量查询

**需要创建的文件**：
```
backend/src/main/java/io/github/legacygraph/service/VectorRetrievalService.java
backend/src/main/java/io/github/legacygraph/controller/VectorController.java
```

**核心功能**：
```java
// 批量向量化文档/代码片段
void batchUpsertVectors(Long projectId, List<VectorDocument> docs);

// 语义检索召回相关证据
List<VectorDocument> semanticSearch(Long projectId, String query, int topK, String chunkType);

// 根据相似度查找可能重复的节点
List<GraphNode> findSimilarNodes(Long projectId, String nodeName, double threshold);
```

---

### 2. 图谱合并核心算法 (P0)

**状态**：⚠️ 占位符实现，核心算法未完成

**文件位置**：`backend/src/main/java/io/github/legacygraph/service/GraphMergeService.java`

**设计文档要求**：
- 三阶段合并算法：候选生成 → 特征打分 → 决策审核
- 支持多种相似度计算：名称相似度、向量相似度、结构相似度、邻居相似度、证据重叠度

**当前问题**：
```java
// 当前硬编码的分数，需要实现真实计算
double semanticScore = 0.5;  // 应该从 pgvector 查询余弦相似度
double structScore = 0.5;    // 应该解析 properties JSON 计算结构特征
double neighborScore = 0.5;  // 应该计算共同邻居占比
double evidenceScore = 0.5;  // 应该计算证据ID交集

// executeMerge 仅删除节点，未合并别名、证据、属性
```

**需要实现的功能**：
- [ ] pgvector 余弦相似度查询
- [ ] 节点结构特征提取与比较
- [ ] 邻居节点相似度计算
- [ ] 证据重叠度计算
- [ ] 节点属性合并策略（别名、证据、置信度）
- [ ] 关系合并与去重

---

### 3. 测试结果反向更新图谱置信度 (P0)

**状态**：❌ 仅接口骨架，未实现业务逻辑

**文件位置**：`backend/src/main/java/io/github/legacygraph/service/GraphValidatorService.java`

**设计文档要求**：
- 测试执行成功 → 提升相关节点和关系的置信度
- 测试执行失败 → 降低相关节点和关系的置信度
- 多次测试验证 → 置信度趋近于 1.0
- 支持按测试类型加权（API 测试 > DB 断言 > E2E 测试）

**需要实现的功能**：
```java
// 根据测试结果更新图谱置信度
void updateConfidenceByTestResult(TestResult result);

// 计算测试验证的加权分数
double calculateTestWeightScore(TestCase testCase, TestResult result);

// 批量更新节点置信度
void batchUpdateNodeConfidence(List<UUID> nodeIds, double delta);

// 测试结果回调处理接口
void handleTestCallback(Long projectId, TestCallbackRequest request);
```

---

### 4. 报告生成服务 (P0)

**状态**：❌ 完全缺失

**设计文档要求**：
- 迁移就绪度报告
- 置信度趋势统计
- 测试覆盖率统计
- 图谱质量报告

**需要创建的文件**：
```
backend/src/main/java/io/github/legacygraph/service/ReportingService.java
backend/src/main/java/io/github/legacygraph/controller/ReportController.java
backend/src/main/java/io/github/legacygraph/dto/ReportDTO.java
```

**核心功能**：
```java
// 生成迁移就绪度报告
MigrationReadinessReport generateMigrationReport(Long projectId);

// 生成置信度趋势报告
ConfidenceTrendReport generateConfidenceTrend(Long projectId, DateRange range);

// 生成测试覆盖率报告
TestCoverageReport generateTestCoverageReport(Long projectId);

// 生成图谱质量报告
GraphQualityReport generateGraphQualityReport(Long projectId);

// 导出报告（PDF/Excel）
byte[] exportReport(Long reportId, String format);
```

---

### 5. Agent 预处理和后处理逻辑 (P1)

**状态**：⚠️ 有骨架，业务逻辑不完整

**涉及文件**：
- `agent/CodeFactAgent.java` - 缺少代码解析、AST 分析逻辑
- `agent/DocUnderstandingAgent.java` - 缺少文档分块、标题层级解析
- `agent/FeatureMappingAgent.java` - 缺少前端 API 与后端 Controller 自动对齐逻辑
- `agent/TestCaseAgent.java` - 缺少多场景（正常/异常/边界）生成逻辑
- `agent/ReviewAgent.java` - 缺少证据自动整理、冲突检测逻辑

**需要补充的功能**：

**CodeFactAgent**：
- [ ] Java 代码 AST 解析
- [ ] 方法调用链分析
- [ ] 注解元数据提取
- [ ] 参数和返回值语义推断

**DocUnderstandingAgent**：
- [ ] 文档标题层级解析
- [ ] 智能分块（保持语义完整性）
- [ ] 目录结构识别
- [ ] 表格内容结构化提取

**FeatureMappingAgent**：
- [ ] 前端 API 路径规范化
- [ ] 后端接口签名匹配
- [ ] 权限注解关联
- [ ] 功能相似度打分算法

---

### 6. 数据库断言执行器 (P1)

**状态**：⚠️ 占位符实现

**文件位置**：`backend/src/main/java/io/github/legacygraph/test/ApiTestExecutor.java`

**当前问题**：
```java
// DB_EXISTS 断言类型空实现，无 JDBC 连接逻辑
case DB_EXISTS:
    // TODO: 执行 JDBC 查询验证数据存在
    break;
```

**需要实现的功能**：
- [ ] JDBC 连接管理
- [ ] SQL 查询执行与结果验证
- [ ] 数据快照对比
- [ ] 支持多种断言类型（存在、不存在、数量、字段值）
- [ ] 独立的 `DbAssertionExecutor.java` 服务

---

### 7. E2E 测试执行器 (P1)

**状态**：⚠️ 仅代码生成，无实际执行逻辑

**文件位置**：`backend/src/main/java/io/github/legacygraph/test/E2eTestExecutor.java`

**当前问题**：
```java
// Playwright 调用仅占位，无实际 CLI 执行逻辑
// TODO: 调用 Playwright CLI 执行生成的测试脚本
```

**需要实现的功能**：
- [ ] Playwright 测试脚本生成
- [ ] CLI 命令执行与输出捕获
- [ ] 浏览器环境配置
- [ ] 截图和视频录制
- [ ] 测试报告解析

---

### 8. 测试执行调度器 (P2)

**状态**：❌ 完全缺失

**设计文档要求**：
- 支持批量测试任务调度
- 并发控制（避免压垮目标系统）
- 任务队列管理
- 执行进度实时通知

**需要创建的文件**：
```
backend/src/main/java/io/github/legacygraph/task/TestExecutionScheduler.java
```

---

### 9. Controller 缺失接口

| 缺失接口 | 所属 Controller | 优先级 |
|---------|-----------------|--------|
| POST /api/projects/{id}/vector/upsert | VectorController | 🔴 P0 |
| POST /api/projects/{id}/vector/search | VectorController | 🔴 P0 |
| POST /api/projects/{id}/graph/merge | GraphQueryController | 🟠 P1 |
| POST /api/tests/results/callback | TestCaseController | 🟠 P1 |
| POST /api/extract/facts/code | FactController | 🟡 P2 |
| POST /api/extract/facts/doc | FactController | 🟡 P2 |
| GET /api/projects/{id}/reports/list | ReportController | 🟠 P1 |
| POST /api/projects/{id}/reports/generate | ReportController | 🟠 P1 |

---

## 🔵 前端未完成功能

### 1. 页面完整性缺失 (P0)

**设计文档要求的页面，当前缺失**：

| 页面 | 路由 | 优先级 | 说明 |
|------|------|--------|------|
| 总览看板 Dashboard | `/dashboard` | 🔴 P0 | 项目理解进度、置信度趋势、测试覆盖率 |
| 项目概览 | `/projects/:id/overview` | 🔴 P0 | 单个项目的统计概览 |
| 统一图谱 | `/projects/:id/graphs/unified` | 🟠 P1 | 三类图谱融合视图 |
| 迁移风险 | `/projects/:id/migration/risks` | 🟠 P1 | 迁移风险识别与评估 |
| 测试执行 | `/projects/:id/test-runs` | 🟠 P1 | 测试执行列表、进度、日志 |
| 系统管理 - 用户 | `/system/users` | 🟡 P2 | 用户管理 |
| 系统管理 - 字典 | `/system/dictionaries` | 🟡 P2 | 字典配置 |
| 系统管理 - 配置 | `/system/settings` | 🟡 P2 | 系统配置 |

**需要创建的文件**：
```
frontend/src/views/dashboard/Index.vue
frontend/src/views/project/ProjectOverview.vue
frontend/src/views/graph/UnifiedGraph.vue
frontend/src/views/migration/RiskList.vue
frontend/src/views/migration/RiskDetail.vue
frontend/src/views/test/TestRunList.vue
frontend/src/views/test/TestRunDetail.vue
frontend/src/views/system/UserList.vue
frontend/src/views/system/DictionaryList.vue
frontend/src/views/system/Settings.vue
```

---

### 2. 图谱展示与交互 (P0)

**状态**：⚠️ 仅基础展示，核心交互缺失

**当前技术栈问题**：
- 设计文档要求使用 **AntV G6**
- 实际代码使用 **@vue-flow/core**
- 导致 G6 提供的复杂节点渲染、布局算法、交互事件无法使用

**需要实现的核心交互**：

| 功能 | 设计文档要求 | 状态 | 优先级 |
|------|-------------|------|--------|
| 图谱版本切换 | 查看历史图谱版本对比 | ❌ 缺失 | 🔴 P0 |
| 重新布局 | 多种布局算法切换 | ❌ 缺失 | 🟡 P2 |
| 路径分析 | 选择起点终点计算最短路径 | ❌ 缺失 | 🟠 P1 |
| 影响分析 | 选中节点分析上下游影响 | ❌ 缺失 | 🟠 P1 |
| 图谱导出 | PNG / SVG / JSON / CSV | ❌ 缺失 | 🟡 P2 |
| 全屏查看 | 全屏模式浏览图谱 | ❌ 缺失 | 🟡 P2 |
| 节点搜索 | 按名称快速定位节点 | ❌ 缺失 | 🟠 P1 |
| 邻居展开 | 1跳/2跳邻居按需展开 | ❌ 缺失 | 🟠 P1 |

**节点和边样式缺失**：
- [ ] 业务域节点（圆角矩形 + 专属颜色）
- [ ] 业务流程节点（胶囊形）
- [ ] 业务对象节点（椭圆）
- [ ] 业务规则节点（六边形）
- [ ] ApiEndpoint 节点（矩形 + 图标）
- [ ] SqlStatement 节点（菱形）
- [ ] Table 节点（数据库表图标）
- [ ] 置信度视觉区分（虚线边框、警告标记）

**性能优化缺失**：
- [ ] 默认加载核心图（不超过 1000 节点）
- [ ] 按需展开邻居（点击后加载）
- [ ] 节点聚合（按包、模块分组）
- [ ] 字段级默认隐藏（数据血缘默认表级展示）
- [ ] Web Worker 布局计算（大图谱不阻塞主线程）

**需要创建的组件**：
```
frontend/src/components/graph/GraphCanvas.vue          # AntV G6 画布
frontend/src/components/graph/GraphToolbar.vue         # 图谱工具栏
frontend/src/components/graph/GraphMiniMap.vue         # 小地图导航
frontend/src/components/graph/GraphPathPanel.vue       # 路径分析面板
frontend/src/components/graph/NodeLegend.vue           # 节点图例
```

---

### 3. 人工审核功能 (P0)

**状态**：⚠️ 仅有基础列表，核心功能缺失

**文件位置**：`frontend/src/views/review/ReviewList.vue`

**需要补充的功能**：

| 功能 | 设计文档要求 | 状态 | 优先级 |
|------|-------------|------|--------|
| 审核对象类型过滤 | NODE / EDGE / RULE / TEST_ASSERTION | ❌ 缺失 | 🔴 P0 |
| 审核优先级 | HIGH / MEDIUM / LOW | ❌ 缺失 | 🟠 P1 |
| 审核指派 | 分配给具体审核人 | ❌ 缺失 | 🟡 P2 |
| 批量审核 | 批量通过/驳回 | ❌ 缺失 | 🟠 P1 |
| 修改节点属性 | 审核时可修正节点名称、类型 | ❌ 缺失 | 🔴 P0 |
| 合并/拆分节点 | 节点维护操作 | ❌ 缺失 | 🟠 P1 |
| 需要更多证据 | 标记节点需要补充扫描 | ❌ 缺失 | 🟡 P2 |

**需要创建的组件**：
```
frontend/src/components/review/ReviewActionPanel.vue
frontend/src/components/review/NodeMergeDialog.vue
frontend/src/components/review/PropertyEditDialog.vue
```

---

### 4. 测试用例编辑器与执行 (P0)

**状态**：⚠️ 仅有列表，编辑和执行功能缺失

**文件位置**：`frontend/src/views/test/TestCaseList.vue`

**需要实现的功能**：

**测试用例编辑器**：
- [ ] HTTP 请求配置（Method、URL、Headers、Body）
- [ ] 断言规则编辑（状态码、JSONPath、数据库断言）
- [ ] 前置条件配置
- [ ] 关联图谱节点
- [ ] 测试运行预览
- [ ] 多场景管理（正常/异常/边界）

**测试执行功能**：
- [ ] 执行进度实时展示
- [ ] 完整请求响应日志查看
- [ ] 断言结果对比（预期值 vs 实际值）
- [ ] 失败用例重跑（单个/批量）
- [ ] 图谱状态回写可视化

**需要创建的文件**：
```
frontend/src/views/test/TestCaseEditor.vue
frontend/src/views/test/TestRunDetail.vue
frontend/src/components/test/TestCaseForm.vue
frontend/src/components/test/AssertionEditor.vue
frontend/src/components/test/TestExecutionLog.vue
```

---

### 5. API 调用完整性 (P0)

**缺失的 API 模块文件**：
```
frontend/src/api/auth.api.ts           # 登录、登出、获取当前用户信息
frontend/src/api/source.api.ts         # 代码仓库、数据库、文档管理
frontend/src/api/fact.api.ts           # 原子事实查询、证据关联
frontend/src/api/test-run.api.ts       # 测试执行管理
frontend/src/api/system.api.ts         # 系统管理接口
frontend/src/api/report.api.ts         # 报告生成和导出
```

**现有 API 缺失接口（对比设计文档）**：

| API 分类 | 设计文档接口数 | 实际实现 | 缺失率 |
|---------|---------------|----------|--------|
| 项目管理 | 5 | 4 | 20% |
| 扫描任务 | 6 | 3 | 50% |
| 图谱查询 | 8 | 4 | 50% |
| 人工审核 | 5 | 2 | 60% |
| 测试管理 | 6 | 2 | 67% |
| 验证报告 | 4 | 1 | 75% |

**核心缺失接口示例**：
```typescript
// 图谱相关
GET /api/projects/{id}/graphs/{type}/versions
GET /api/projects/{id}/graphs/nodes/{nodeId}/neighbors
GET /api/projects/{id}/graphs/path-analysis
GET /api/projects/{id}/graphs/impact-analysis

// 审核相关
POST /api/projects/{id}/reviews/{reviewId}/modify
POST /api/projects/{id}/reviews/batch-approve

// 测试相关
GET /api/projects/{id}/test-runs/{runId}/case-results
GET /api/projects/{id}/test-runs/{runId}/logs
```

---

### 6. 请求封装缺失功能 (P0)

**文件位置**：`frontend/src/api/index.ts`

**设计文档要求，当前缺失**：
- [ ] Token 注入和自动刷新
- [ ] 401 自动跳转登录
- [ ] 请求取消和防重复提交
- [ ] 下载文件处理
- [ ] TraceID 显示和传递
- [ ] 全局错误提示统一处理

**需要创建的文件**：
```
frontend/src/utils/request.ts          # 统一请求封装
frontend/src/utils/download.ts         # 文件下载工具
frontend/src/utils/permission.ts       # 权限判断工具
```

---

### 7. 状态管理 Store 缺失 (P1)

**缺失的 Store 文件**：
```
frontend/src/stores/test.store.ts      # 测试用例选择、执行进度、结果缓存
```

**现有 Store 功能缺失**：

**user.store.ts**：
- [ ] refreshToken 管理
- [ ] 权限列表存储和校验
- [ ] 用户信息完整字段

**project.store.ts**：
- [ ] 项目列表缓存
- [ ] 当前项目完整信息（包含资料接入状态）
- [ ] 项目上下文校验

**graph.store.ts**：
- [ ] 局部展开数据管理
- [ ] 图谱版本切换历史
- [ ] 图谱统计数据缓存

**task.store.ts**：
- [ ] 轮询任务控制机制
- [ ] 全局任务通知
- [ ] 任务进度实时更新（WebSocket）

---

### 8. 权限控制体系缺失 (P1)

**设计文档要求四层权限控制**：

| 层级 | 要求 | 状态 |
|------|------|------|
| 路由权限 | 登录校验、权限编码校验 | ⚠️ 仅登录校验，无权限编码 |
| 菜单权限 | 动态菜单渲染 | ❌ 缺失 |
| 按钮权限 | v-permission 指令、hasPermission() 函数 | ❌ 缺失 |
| 接口权限 | 后端权限编码传递到前端 | ❌ 缺失 |

**需要实现的功能**：
- [ ] 权限指令 `v-permission`
- [ ] 权限判断函数 `hasPermission()`
- [ ] 动态菜单生成
- [ ] 路由守卫权限校验
- [ ] 无权限页面提示

---

### 9. 通用基础组件缺失 (P2)

| 组件名称 | 设计文档要求 | 优先级 |
|---------|-------------|--------|
| BaseTable | 通用表格组件，支持分页、排序、过滤 | 🟠 P1 |
| SearchForm | 通用搜索表单组件 | 🟡 P2 |
| CodeViewer | Monaco Editor 代码查看器（SQL、Java） | 🟠 P1 |
| JsonViewer | JSON 格式化查看器 | 🟡 P2 |
| EmptyState | 空状态统一组件 | 🟡 P2 |
| StatusTag | 状态标签统一组件 | 🟡 P2 |
| ConfidenceBadge | 置信度徽章组件 | 🟡 P2 |

**需要创建的文件**：
```
frontend/src/components/common/BaseTable.vue
frontend/src/components/common/SearchForm.vue
frontend/src/components/common/CodeViewer.vue
frontend/src/components/common/JsonViewer.vue
frontend/src/components/common/EmptyState.vue
frontend/src/components/common/StatusTag.vue
frontend/src/components/common/ConfidenceBadge.vue
```

---

### 10. 类型定义缺失 (P2)

**文件位置**：`frontend/src/types/index.ts`

**对比设计文档，缺失的类型**：

```typescript
// 图谱相关
enum GraphNodeType { ... }
enum GraphEdgeType { ... }

// 审核相关
enum ReviewStatus { ... }
enum ReviewPriority { ... }

// 测试相关
interface Evidence { ... }
interface TestCase { ... }
interface TestRun { ... }
interface TestAssertion { ... }
interface TestResult { ... }

// 通用响应
interface ApiResponse<T> {
  code: number
  message: string
  data: T
  traceId: string
}

// 分页响应
interface PageResponse<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}
```

---

## 📊 实施优先级和建议

### 第一阶段（1-2 周）：核心能力修复

**目标**：达到 MVP 可用状态，完成度 70%+

| 任务 | 优先级 | 预估工作量 |
|------|--------|-----------|
| 向量检索服务实现 | 🔴 P0 | 3 天 |
| GraphMergeService 核心算法补全 | 🔴 P0 | 5 天 |
| 测试结果回传图谱置信度 | 🔴 P0 | 3 天 |
| 前端 API 请求封装完善 | 🔴 P0 | 2 天 |
| Dashboard 总览看板页面 | 🔴 P0 | 3 天 |
| 节点详情抽屉证据展示完善 | 🔴 P0 | 2 天 |

---

### 第二阶段（2-3 周）：验证闭环

**目标**：完成测试验证和报告闭环，完成度 85%+

| 任务 | 优先级 | 预估工作量 |
|------|--------|-----------|
| ReportingService 报告生成服务 | 🟠 P1 | 5 天 |
| 数据库断言执行器实现 | 🟠 P1 | 3 天 |
| Agent 预处理和后处理逻辑补全 | 🟠 P1 | 5 天 |
| 图谱版本切换和对比功能 | 🟠 P1 | 3 天 |
| 人工审核完整操作流程 | 🟠 P1 | 4 天 |
| 测试用例编辑器 | 🟠 P1 | 4 天 |
| 测试执行页面 | 🟠 P1 | 3 天 |

---

### 第三阶段（2-3 周）：体验优化

**目标**：完善交互和性能，完成度 95%+

| 任务 | 优先级 | 预估工作量 |
|------|--------|-----------|
| AntV G6 图谱完整交互（搜索、路径分析、影响分析） | 🟠 P1 | 1 周 |
| 权限控制体系完整实现 | 🟡 P2 | 3 天 |
| 通用基础组件抽取 | 🟡 P2 | 3 天 |
| 图谱性能优化（按需加载、聚合、Web Worker） | 🟡 P2 | 5 天 |
| E2E 测试执行器完善 | 🟡 P2 | 4 天 |

---

## 📝 总结

### 高优先级遗漏（P0）
- 向量检索服务
- 图谱合并核心算法
- 测试结果反向更新置信度
- 报告生成服务
- Dashboard 总览看板
- 图谱核心交互功能
- 人工审核完整流程
- 测试用例编辑器和执行页面

### 中优先级遗漏（P1）
- Agent 预处理和后处理逻辑
- 数据库断言执行器
- E2E 测试执行器
- 测试执行调度器
- 权限控制体系
- 通用基础组件
- 类型定义完善

### 低优先级遗漏（P2）
- 系统管理页面
- 迁移风险页面
- 图谱高级功能（导出、全屏等）

---

**注**：本清单基于设计文档和现有代码对比生成，实际开发中可能根据需求调整优先级。建议按照上述三阶段计划逐步推进。
