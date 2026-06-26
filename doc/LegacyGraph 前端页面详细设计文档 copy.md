# LegacyGraph 老项目 AI 图谱理解平台：配套前端页面详细设计文档

> 文档版本：v1.0  
> 适用范围：LegacyGraph 老项目理解平台前端管理端  
> 推荐第一版技术范围：Java Spring Boot + Vue 老项目扫描、图谱展示、人工审核、测试用例生成、测试执行结果查看  
> 更新时间：2026-06-26

---

## 1. 文档目标

本文档用于指导 `LegacyGraph 老项目 AI 图谱理解平台` 的前端页面建设，目标是把后端抽取出的事实、图谱、AI 推断、人工审核结果、测试执行结果，以可视化、可追溯、可操作的方式呈现给开发、测试、架构师和业务人员。

前端不是简单的后台管理系统，而是老项目理解和迁移的工作台，核心职责包括：

1. 接入老项目材料：代码仓库、数据库连接、产品文档、接口文档、部署配置。
2. 发起扫描任务：后端代码扫描、前端代码扫描、数据库扫描、文档解析、图谱构建、测试生成。
3. 展示统一知识图谱，并拆分为业务图谱、功能图谱、代码图谱、数据血缘图谱、运行链路图谱。
4. 展示每个图谱节点和关系的证据来源，包括文件路径、代码行号、SQL、表字段、文档段落、测试结果。
5. 支持人工审核低置信度节点和关系。
6. 支持基于图谱生成测试用例，并查看 API 测试、E2E 测试、数据库断言结果。
7. 支持图谱验证结果回写，形成“抽取 -> 图谱 -> 测试 -> 验证 -> 修正”的闭环。

---

## 2. 前端定位

### 2.1 用户角色

| 角色 | 主要诉求 | 主要使用页面 |
|---|---|---|
| 系统管理员 | 管理用户、角色、项目、全局配置 | 系统管理、用户管理、项目管理 |
| 架构师 | 查看系统整体结构、代码调用链、数据血缘、迁移边界 | 图谱中心、代码图谱、数据血缘、运行链路 |
| 后端开发 | 查看接口、Controller、Service、Mapper、SQL、表关系 | 代码图谱、事实管理、接口详情、SQL 详情 |
| 前端开发 | 查看页面、路由、菜单、按钮、API 调用关系 | 功能图谱、页面详情、接口调用链 |
| 测试人员 | 生成测试用例、执行测试、查看断言结果 | 测试用例、测试执行、验证报告 |
| 业务人员 / 产品经理 | 查看业务模块、业务流程、业务对象、规则和状态流转 | 业务图谱、人工审核、业务节点详情 |
| 迁移负责人 | 判断哪些模块可迁移、哪些依赖复杂、哪些缺少证据 | 总览看板、风险清单、图谱验证报告 |

### 2.2 前端核心原则

1. **图谱必须可追溯**：任何节点和关系点击后都能看到证据。
2. **AI 结论必须可审核**：AI 推断内容不能直接当作确定事实，必须显示置信度和来源。
3. **复杂图谱要分层展示**：默认展示业务可理解视图，允许逐层下钻到代码和数据。
4. **页面服务迁移，不只服务展示**：每个图谱节点都要能判断迁移状态、测试覆盖率、风险等级。
5. **低置信度优先处理**：人工审核页面要优先推送影响大的低置信度节点。
6. **前后端契约稳定**：图谱、任务、测试、审核等通用数据结构必须保持统一。

---

## 3. 前端技术选型

### 3.1 推荐技术栈

| 类型 | 技术 | 建议版本 / 范围 | 选择原因 |
|---|---|---|---|
| 前端框架 | Vue 3 | Vue 3.x | 与国内 Java 后台管理系统生态匹配，适合企业级中后台 |
| 构建工具 | Vite | Vite 8.x 或企业锁定版本 | 开发启动快，适合 TypeScript + Vue 工程 |
| 语言 | TypeScript | 5.x | 图谱数据结构复杂，必须强类型约束 |
| UI 组件库 | Ant Design Vue | 4.x | 表格、表单、抽屉、步骤条、树形控件成熟 |
| 状态管理 | Pinia | 3.x | Vue 官方推荐生态，适合模块化 store |
| 路由 | Vue Router | 4.x | Vue 3 标准路由方案 |
| HTTP | Axios | 1.x | 企业项目常用，拦截器成熟 |
| 图谱渲染 | AntV G6 | 5.x | 支持复杂节点、边、布局、交互和插件扩展 |
| 辅助图表 | ECharts | 6.x 或 5.x 稳定版 | 总览看板、统计图、趋势图 |
| 代码预览 | Monaco Editor | 0.5x | 展示代码证据、SQL、JSON、测试脚本 |
| Markdown 预览 | md-editor-v3 / markdown-it | 视项目定 | 展示文档解析结果、AI 总结 |
| 工具函数 | lodash-es、dayjs | 最新稳定 | 数据处理、时间格式化 |
| 单元测试 | Vitest | 与 Vite 配套 | 组件和工具函数测试 |
| E2E 测试 | Playwright | 最新稳定 | 测试关键业务流 |
| 代码规范 | ESLint + Prettier | 最新稳定 | 保证多人协作一致性 |

### 3.2 版本说明

截至本文档生成日期，Node.js 官方页面显示 v24 为 LTS，v26 为 Current；生产应用应使用 Active LTS 或 Maintenance LTS 版本，因此前端构建环境建议使用 Node.js 24 LTS。Vite 官方文档页面显示当前文档版本为 v8.1.0。Vue 官方文档明确当前是 Vue 3 文档，并推荐在完整应用中使用 Composition API + Single File Components。G6 官方文档定位为图可视化引擎，适合作为本平台图谱画布能力的基础。

### 3.3 第一版技术约束

第一版不建议引入微前端，不建议做 SSR，不建议做复杂主题系统。

第一版建议采用：

```text
Vue 3 + TypeScript + Vite + Ant Design Vue + Pinia + Vue Router + Axios + AntV G6
```

原因：

1. 管理端页面多，使用 Vue + Ant Design Vue 开发效率高。
2. 图谱交互复杂，G6 更适合做节点、边、布局、路径高亮和交互扩展。
3. TypeScript 可以降低图谱节点类型、边类型、测试断言类型混乱的问题。
4. Vite 工程简单，适合快速构建 MVP。

---

## 4. 前端整体架构

### 4.1 架构分层

```text
┌─────────────────────────────────────────────────────────────┐
│                         页面层 Pages                         │
│  项目管理 / 扫描任务 / 图谱中心 / 事实管理 / 人工审核 / 测试   │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                       业务组件层 Widgets                      │
│  GraphCanvas / EvidencePanel / TaskTimeline / TestCaseEditor │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                       通用组件层 Components                   │
│  BaseTable / SearchForm / JsonViewer / CodeViewer / TagGroup │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                         状态层 Store                         │
│  userStore / projectStore / graphStore / taskStore / testStore│
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                         服务层 API                           │
│  projectApi / scanApi / graphApi / factApi / reviewApi / testApi│
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                         后端接口                              │
│  Spring Boot REST API / WebSocket / 文件下载 / SSE             │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 前端应用结构

建议仓库名：`legacy-graph-web`

```text
legacy-graph-web/
├── public/
├── src/
│   ├── api/                         # 接口请求
│   │   ├── request.ts
│   │   ├── project.api.ts
│   │   ├── source.api.ts
│   │   ├── scan.api.ts
│   │   ├── graph.api.ts
│   │   ├── fact.api.ts
│   │   ├── review.api.ts
│   │   ├── test-case.api.ts
│   │   ├── test-run.api.ts
│   │   └── system.api.ts
│   ├── assets/
│   ├── components/                  # 通用基础组件
│   │   ├── BaseTable/
│   │   ├── SearchForm/
│   │   ├── CodeViewer/
│   │   ├── JsonViewer/
│   │   ├── EmptyState/
│   │   ├── ConfidenceBadge/
│   │   └── EvidenceList/
│   ├── widgets/                     # 业务复合组件
│   │   ├── graph/
│   │   │   ├── GraphCanvas/
│   │   │   ├── GraphToolbar/
│   │   │   ├── GraphMiniMap/
│   │   │   ├── NodeDetailDrawer/
│   │   │   ├── EdgeDetailDrawer/
│   │   │   └── GraphPathPanel/
│   │   ├── task/
│   │   ├── review/
│   │   ├── test/
│   │   └── source/
│   ├── layouts/
│   │   ├── BasicLayout.vue
│   │   ├── BlankLayout.vue
│   │   └── ProjectLayout.vue
│   ├── router/
│   │   ├── index.ts
│   │   ├── routes.ts
│   │   └── guard.ts
│   ├── stores/
│   │   ├── app.store.ts
│   │   ├── user.store.ts
│   │   ├── project.store.ts
│   │   ├── graph.store.ts
│   │   ├── task.store.ts
│   │   └── test.store.ts
│   ├── types/
│   │   ├── common.ts
│   │   ├── project.ts
│   │   ├── source.ts
│   │   ├── scan.ts
│   │   ├── graph.ts
│   │   ├── fact.ts
│   │   ├── review.ts
│   │   └── test.ts
│   ├── utils/
│   │   ├── auth.ts
│   │   ├── date.ts
│   │   ├── graph-style.ts
│   │   ├── download.ts
│   │   └── permission.ts
│   ├── views/
│   │   ├── dashboard/
│   │   ├── project/
│   │   ├── source/
│   │   ├── scan/
│   │   ├── graph/
│   │   ├── fact/
│   │   ├── review/
│   │   ├── test-case/
│   │   ├── test-run/
│   │   ├── report/
│   │   └── system/
│   ├── App.vue
│   └── main.ts
├── tests/
├── .env.development
├── .env.test
├── .env.production
├── package.json
├── tsconfig.json
├── vite.config.ts
└── README.md
```

---

## 5. 菜单与路由设计

### 5.1 一级菜单

| 一级菜单 | 二级菜单 | 路由 | 说明 |
|---|---|---|---|
| 工作台 | 总览看板 | `/dashboard` | 当前项目理解进度、风险、测试通过率 |
| 项目管理 | 项目列表 | `/projects` | 管理老项目接入 |
| 项目管理 | 项目详情 | `/projects/:projectId/overview` | 项目基础信息、接入状态 |
| 资料接入 | 代码仓库 | `/projects/:projectId/sources/repos` | 前后端仓库接入 |
| 资料接入 | 数据库连接 | `/projects/:projectId/sources/databases` | 数据库配置和表扫描 |
| 资料接入 | 文档资料 | `/projects/:projectId/sources/docs` | 产品文档、接口文档上传 |
| 扫描任务 | 任务列表 | `/projects/:projectId/scans` | 所有扫描任务记录 |
| 扫描任务 | 新建扫描 | `/projects/:projectId/scans/create` | 创建扫描任务 |
| 图谱中心 | 统一图谱 | `/projects/:projectId/graphs/unified` | 全量知识图谱 |
| 图谱中心 | 业务图谱 | `/projects/:projectId/graphs/business` | 业务域、流程、对象、规则 |
| 图谱中心 | 功能图谱 | `/projects/:projectId/graphs/feature` | 菜单、页面、按钮、接口 |
| 图谱中心 | 代码图谱 | `/projects/:projectId/graphs/code` | Controller、Service、Mapper、SQL |
| 图谱中心 | 数据血缘 | `/projects/:projectId/graphs/data-lineage` | 表、字段、读写链路 |
| 图谱中心 | 运行链路 | `/projects/:projectId/graphs/runtime` | 定时任务、MQ、外部接口、调用链 |
| 事实管理 | 事实列表 | `/projects/:projectId/facts` | 原子事实查询 |
| 事实管理 | 证据检索 | `/projects/:projectId/evidence` | 按文件、表、文档、测试结果查证据 |
| 人工审核 | 审核队列 | `/projects/:projectId/reviews` | 低置信度节点和关系审核 |
| 人工审核 | 审核历史 | `/projects/:projectId/reviews/history` | 审核记录 |
| 测试验证 | 测试用例 | `/projects/:projectId/test-cases` | 图谱生成的测试用例 |
| 测试验证 | 测试执行 | `/projects/:projectId/test-runs` | 测试任务、执行日志 |
| 测试验证 | 验证报告 | `/projects/:projectId/reports/validation` | 图谱正确性验证结果 |
| 迁移辅助 | 迁移风险 | `/projects/:projectId/migration/risks` | 模块风险、依赖复杂度、缺失证据 |
| 迁移辅助 | 迁移清单 | `/projects/:projectId/migration/items` | 可迁移对象、待确认对象 |
| 系统管理 | 用户管理 | `/system/users` | 用户和角色 |
| 系统管理 | 字典配置 | `/system/dicts` | 节点类型、关系类型、置信度规则 |

### 5.2 路由权限编码

| 权限编码 | 说明 |
|---|---|
| `project:view` | 查看项目 |
| `project:create` | 新建项目 |
| `source:config` | 配置代码仓库、数据库、文档 |
| `scan:create` | 发起扫描 |
| `scan:stop` | 停止扫描任务 |
| `graph:view` | 查看图谱 |
| `graph:export` | 导出图谱 |
| `fact:view` | 查看事实和证据 |
| `review:approve` | 审核通过 |
| `review:reject` | 审核驳回 |
| `testcase:generate` | 生成测试用例 |
| `testrun:create` | 执行测试 |
| `report:export` | 导出报告 |
| `system:manage` | 系统管理 |

---

## 6. 全局交互设计

### 6.1 基础布局

`BasicLayout` 包含：

1. 顶部栏：平台名称、当前项目选择器、全局搜索、消息通知、用户菜单。
2. 左侧菜单：根据权限和项目上下文展示。
3. 主内容区：面包屑、页面标题、页面操作区、内容区。
4. 右侧可选抽屉：用于节点详情、证据详情、任务日志。

### 6.2 当前项目选择器

位置：顶部栏左侧。

字段：

| 字段 | 说明 |
|---|---|
| 项目名称 | 当前分析的老项目 |
| 项目编码 | projectCode |
| 最近扫描时间 | lastScanTime |
| 图谱状态 | 未构建 / 构建中 / 已构建 / 部分失败 |

交互：

1. 切换项目后，刷新所有项目上下文路由。
2. 如果当前页面是项目内页面，则自动跳转到新项目同类页面。
3. 如果用户无该项目权限，则提示并跳转项目列表。

### 6.3 全局搜索

入口：顶部搜索框。

支持搜索对象：

1. 业务流程。
2. 功能点。
3. 菜单和页面。
4. API 接口。
5. Java 类和方法。
6. Mapper 方法。
7. SQL。
8. 数据库表和字段。
9. 文档段落。
10. 测试用例。

搜索结果分组展示：

```text
业务对象
功能页面
后端接口
代码方法
数据库表
证据文件
测试用例
```

点击结果后跳转到对应详情页或打开右侧详情抽屉。

---

## 7. 页面详细设计

## 7.1 登录页

### 路由

`/login`

### 页面目标

用户登录平台，获取访问 Token 和菜单权限。

### 页面元素

| 区域 | 元素 | 说明 |
|---|---|---|
| 登录表单 | 用户名 | 必填 |
| 登录表单 | 密码 | 必填 |
| 登录表单 | 验证码 | 可选，第一版可不做 |
| 操作区 | 登录按钮 | 调用登录接口 |
| 操作区 | 记住我 | 保存用户名，不保存密码 |

### 接口

```http
POST /api/auth/login
```

请求：

```json
{
  "username": "admin",
  "password": "******"
}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "accessToken": "jwt-token",
    "refreshToken": "refresh-token",
    "expiresIn": 7200,
    "user": {
      "id": 1,
      "username": "admin",
      "displayName": "系统管理员",
      "permissions": ["project:view", "graph:view"]
    }
  }
}
```

### 验收标准

1. 登录成功后进入 `/dashboard`。
2. 登录失败显示后端错误信息。
3. Token 过期后自动跳转登录页。

---

## 7.2 工作台：总览看板

### 路由

`/dashboard`

### 页面目标

展示当前项目的理解进度、图谱构建情况、风险、测试验证情况，让迁移负责人一眼知道老项目理解到什么程度。

### 页面布局

```text
┌─────────────────────────────────────────────────────────────┐
│ 项目概览卡片：项目名称、技术栈、最近扫描时间、图谱状态          │
├──────────────┬──────────────┬──────────────┬──────────────┤
│ 事实数量      │ 图谱节点数量  │ 图谱关系数量  │ 测试通过率    │
├──────────────────────────────┬──────────────────────────────┤
│ 图谱覆盖率趋势                 │ 扫描任务状态分布               │
├──────────────────────────────┴──────────────────────────────┤
│ 高风险模块列表                                                 │
├─────────────────────────────────────────────────────────────┤
│ 最近扫描任务 / 最近人工审核 / 最近测试执行                      │
└─────────────────────────────────────────────────────────────┘
```

### 展示字段

#### 项目概览

| 字段 | 说明 |
|---|---|
| 项目名称 | 老项目名称 |
| 项目编码 | 唯一编码 |
| 技术栈 | Java / Spring Boot / Vue / PostgreSQL / MyBatis |
| 代码仓库数量 | 后端仓库 + 前端仓库 |
| 数据库数量 | 接入的数据库连接数 |
| 文档数量 | 上传的产品文档、接口文档 |
| 最近扫描时间 | lastScanTime |
| 图谱状态 | NOT_BUILT / BUILDING / READY / PARTIAL_FAILED / FAILED |

#### 指标卡片

| 指标 | 计算逻辑 |
|---|---|
| 事实数量 | `lg_fact` 总数 |
| 图谱节点数量 | `lg_graph_node` 总数 |
| 图谱关系数量 | `lg_graph_edge` 总数 |
| 已验证关系数 | 已被测试用例或人工确认验证的关系数 |
| 测试通过率 | 最近一次测试执行通过用例数 / 总用例数 |
| 低置信度节点数 | confidence < 0.6 的节点数 |
| 缺失证据关系数 | 无 evidence 的关系数 |

### 操作按钮

| 按钮 | 权限 | 行为 |
|---|---|---|
| 新建扫描 | `scan:create` | 跳转新建扫描页 |
| 查看统一图谱 | `graph:view` | 跳转统一图谱 |
| 查看风险清单 | `graph:view` | 跳转迁移风险页 |
| 生成测试用例 | `testcase:generate` | 打开生成测试用例弹窗 |

### 接口

```http
GET /api/projects/{projectId}/dashboard/summary
GET /api/projects/{projectId}/dashboard/coverage-trend
GET /api/projects/{projectId}/dashboard/risk-modules
GET /api/projects/{projectId}/dashboard/recent-activities
```

### 验收标准

1. 页面加载时间小于 3 秒。
2. 无项目时展示引导创建项目。
3. 任一指标点击后可跳转到对应详情列表。
4. 图谱构建失败时展示失败原因入口。

---

## 7.3 项目列表页

### 路由

`/projects`

### 页面目标

管理多个老项目接入，每个项目对应一个独立的代码、数据库、文档、图谱和测试空间。

### 查询条件

| 字段 | 类型 | 说明 |
|---|---|---|
| 项目名称 | 输入框 | 模糊查询 |
| 项目编码 | 输入框 | 精确 / 模糊查询 |
| 项目状态 | 下拉框 | INIT / SCANNING / GRAPH_READY / ARCHIVED |
| 技术栈 | 多选 | Java、Vue、React、PostgreSQL、MySQL |
| 创建时间 | 日期范围 | createdAt |

### 表格字段

| 字段 | 说明 |
|---|---|
| 项目名称 | 点击进入项目概览 |
| 项目编码 | projectCode |
| 业务系统 | 原系统名称 |
| 技术栈 | 标签展示 |
| 图谱状态 | 状态标签 |
| 最近扫描时间 | lastScanTime |
| 低置信度数量 | 点击进入审核队列 |
| 测试通过率 | 最近测试通过率 |
| 创建人 | createdBy |
| 操作 | 详情 / 配置资料 / 发起扫描 / 删除 |

### 新建项目弹窗

字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| 项目名称 | 是 | 老项目名称 |
| 项目编码 | 是 | 英文唯一编码 |
| 原系统名称 | 否 | 老系统对外名称 |
| 项目描述 | 否 | 背景说明 |
| 主要技术栈 | 是 | 多选 |
| 数据库类型 | 是 | PostgreSQL / MySQL / Oracle / SQL Server |
| 负责人 | 否 | 项目负责人 |

### 接口

```http
GET    /api/projects
POST   /api/projects
GET    /api/projects/{projectId}
PUT    /api/projects/{projectId}
DELETE /api/projects/{projectId}
```

### 验收标准

1. 支持分页、筛选、排序。
2. 项目编码前端校验只能包含小写字母、数字、中划线、下划线。
3. 删除项目前必须二次确认。
4. 已有关联扫描任务的项目不允许物理删除，只能归档。

---

## 7.4 项目详情页

### 路由

`/projects/:projectId/overview`

### 页面目标

展示项目基本信息、资料接入完成度、最近扫描结果、图谱构建状态。

### 页面分区

1. 基础信息。
2. 资料接入状态。
3. 扫描能力开关。
4. 图谱状态。
5. 最近活动。
6. 项目级配置。

### 资料接入状态

| 接入项 | 状态 | 说明 |
|---|---|---|
| 后端代码仓库 | 未配置 / 已配置 / 拉取失败 / 已扫描 | Java Spring Boot 项目 |
| 前端代码仓库 | 未配置 / 已配置 / 拉取失败 / 已扫描 | Vue / React 项目 |
| 数据库连接 | 未配置 / 连通 / 失败 / 已扫描 | PostgreSQL / MySQL |
| 产品文档 | 未上传 / 已上传 / 已解析 | Word / PDF / Markdown |
| 接口文档 | 未上传 / 已上传 / 已解析 | Swagger / OpenAPI / Markdown |
| 测试环境 | 未配置 / 连通 / 不可用 | 用于自动测试 |

### 接口

```http
GET /api/projects/{projectId}/overview
PUT /api/projects/{projectId}/settings
```

### 验收标准

1. 能清楚看到哪些资料缺失。
2. 每个未配置项提供“去配置”按钮。
3. 图谱失败时可查看失败任务和错误日志。

---

## 7.5 代码仓库接入页

### 路由

`/projects/:projectId/sources/repos`

### 页面目标

配置老项目的前端代码仓库、后端代码仓库、分支、认证方式和扫描范围。

### 表格字段

| 字段 | 说明 |
|---|---|
| 仓库名称 | repoName |
| 仓库类型 | BACKEND / FRONTEND / COMMON_LIB / DEPLOY_CONFIG |
| Git 地址 | 隐藏敏感 Token |
| 分支 | branch |
| 本地路径 | 后端拉取后的存储路径，只读展示 |
| 扫描范围 | include / exclude |
| 最近拉取时间 | lastPullTime |
| 最近扫描时间 | lastScanTime |
| 状态 | INIT / PULLING / READY / FAILED |
| 操作 | 拉取 / 测试连接 / 编辑 / 删除 |

### 新增仓库表单

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| 仓库名称 | 输入框 | 是 | 自定义名称 |
| 仓库类型 | 单选 | 是 | BACKEND / FRONTEND |
| Git 地址 | 输入框 | 是 | 支持 HTTP(S) / SSH |
| 分支 | 输入框 | 是 | 默认 master/main |
| 认证方式 | 单选 | 是 | NONE / USER_PASSWORD / TOKEN / SSH_KEY |
| 用户名 | 输入框 | 条件必填 | USER_PASSWORD 时必填 |
| 密码 / Token | 密码框 | 条件必填 | 加密保存 |
| SSH Key | 文本域 | 条件必填 | SSH_KEY 时必填 |
| Include Pattern | 多行文本 | 否 | 如 `src/main/java/**` |
| Exclude Pattern | 多行文本 | 否 | 如 `target/**`, `node_modules/**` |

### 交互细节

1. 点击“测试连接”时后端只校验拉取权限，不保存配置。
2. 点击“保存并拉取”时先保存配置，再创建拉取任务。
3. 仓库拉取中时显示进度和日志入口。
4. Token、密码、SSH Key 不允许回显。

### 接口

```http
GET  /api/projects/{projectId}/sources/repos
POST /api/projects/{projectId}/sources/repos
PUT  /api/projects/{projectId}/sources/repos/{repoId}
POST /api/projects/{projectId}/sources/repos/{repoId}/test-connection
POST /api/projects/{projectId}/sources/repos/{repoId}/pull
DELETE /api/projects/{projectId}/sources/repos/{repoId}
```

### 验收标准

1. 后端仓库和前端仓库可分别配置多个。
2. 拉取失败时能查看完整错误原因。
3. 敏感字段不回显、不出现在浏览器缓存和日志中。

---

## 7.6 数据库连接页

### 路由

`/projects/:projectId/sources/databases`

### 页面目标

配置老项目数据库连接，用于表结构扫描、字段注释抽取、SQL 读写关系验证、测试断言。

### 表格字段

| 字段 | 说明 |
|---|---|
| 连接名称 | dbName |
| 数据库类型 | PostgreSQL / MySQL / Oracle |
| Host | 数据库地址 |
| Port | 端口 |
| Database | 数据库名 |
| Schema | PostgreSQL schema |
| 用户名 | username |
| 连接状态 | SUCCESS / FAILED / UNKNOWN |
| 最近扫描时间 | lastScanTime |
| 表数量 | tableCount |
| 操作 | 测试连接 / 扫描表结构 / 编辑 / 删除 |

### 新增连接表单

| 字段 | 必填 | 说明 |
|---|---|---|
| 连接名称 | 是 | 自定义名称 |
| 数据库类型 | 是 | PostgreSQL / MySQL |
| Host | 是 | 数据库地址 |
| Port | 是 | 默认 5432 / 3306 |
| Database | 是 | 数据库名 |
| Schema | 否 | PostgreSQL 可填 public 或业务 schema |
| 用户名 | 是 | db user |
| 密码 | 是 | 加密保存 |
| 只读模式 | 是 | 第一版建议默认只读 |
| Include Tables | 否 | 表名匹配 |
| Exclude Tables | 否 | 排除表 |

### 数据库扫描结果入口

点击“表数量”进入表结构详情页：

`/projects/:projectId/sources/databases/:dbId/tables`

表结构详情字段：

| 字段 | 说明 |
|---|---|
| 表名 | tableName |
| 表注释 | tableComment |
| 字段数 | columnCount |
| 主键 | primaryKey |
| 索引数量 | indexCount |
| 是否被 SQL 使用 | true / false |
| 读接口数量 | readApiCount |
| 写接口数量 | writeApiCount |

### 接口

```http
GET  /api/projects/{projectId}/sources/databases
POST /api/projects/{projectId}/sources/databases
PUT  /api/projects/{projectId}/sources/databases/{dbId}
POST /api/projects/{projectId}/sources/databases/{dbId}/test-connection
POST /api/projects/{projectId}/sources/databases/{dbId}/scan-schema
GET  /api/projects/{projectId}/sources/databases/{dbId}/tables
GET  /api/projects/{projectId}/sources/databases/{dbId}/tables/{tableName}
```

### 验收标准

1. 支持 PostgreSQL、MySQL 第一版接入。
2. 密码不回显。
3. 扫描表结构必须是只读操作。
4. 表详情能展示字段、类型、注释、索引和被哪些接口读写。

---

## 7.7 文档资料页

### 路由

`/projects/:projectId/sources/docs`

### 页面目标

上传和管理产品文档、接口文档、操作手册、数据库说明文档，用于业务图谱和功能图谱构建。

### 表格字段

| 字段 | 说明 |
|---|---|
| 文档名称 | docName |
| 文档类型 | PRODUCT / API / MANUAL / DB_DESIGN / MIGRATION |
| 文件类型 | docx / pdf / md / xlsx |
| 文件大小 | size |
| 上传人 | uploader |
| 上传时间 | uploadTime |
| 解析状态 | UPLOADED / PARSING / PARSED / FAILED |
| 抽取事实数 | factCount |
| 操作 | 预览 / 解析 / 下载 / 删除 |

### 上传表单

| 字段 | 必填 | 说明 |
|---|---|---|
| 文档类型 | 是 | PRODUCT / API / MANUAL 等 |
| 文档文件 | 是 | 支持 docx、pdf、md、xlsx |
| 业务模块 | 否 | 可绑定模块 |
| 备注 | 否 | 描述文档用途 |

### 文档预览

预览页：

`/projects/:projectId/sources/docs/:docId/preview`

能力：

1. 左侧文档目录。
2. 中间文档内容。
3. 右侧抽取事实列表。
4. 点击事实时高亮对应段落。
5. 支持手动标记“这是业务规则 / 这是业务流程 / 这是状态流转”。

### 接口

```http
GET    /api/projects/{projectId}/sources/docs
POST   /api/projects/{projectId}/sources/docs/upload
POST   /api/projects/{projectId}/sources/docs/{docId}/parse
GET    /api/projects/{projectId}/sources/docs/{docId}
GET    /api/projects/{projectId}/sources/docs/{docId}/content
DELETE /api/projects/{projectId}/sources/docs/{docId}
```

### 验收标准

1. 支持拖拽上传。
2. 上传失败显示具体原因。
3. 解析失败保留原文档，不影响重新解析。
4. 文档段落和抽取事实可互相定位。

---

## 7.8 扫描任务列表页

### 路由

`/projects/:projectId/scans`

### 页面目标

查看所有扫描任务，包括代码扫描、数据库扫描、文档解析、图谱构建、测试生成。

### 查询条件

| 字段 | 类型 | 说明 |
|---|---|---|
| 任务类型 | 下拉 | CODE_SCAN / DB_SCAN / DOC_PARSE / GRAPH_BUILD / TEST_GENERATE |
| 任务状态 | 下拉 | WAITING / RUNNING / SUCCESS / FAILED / CANCELED |
| 创建人 | 输入框 | createdBy |
| 创建时间 | 日期范围 | createdAt |

### 表格字段

| 字段 | 说明 |
|---|---|
| 任务编号 | scanNo |
| 任务名称 | scanName |
| 任务类型 | type |
| 状态 | status |
| 当前阶段 | stage |
| 进度 | progress |
| 事实增量 | factCount |
| 节点增量 | nodeCount |
| 关系增量 | edgeCount |
| 开始时间 | startTime |
| 结束时间 | endTime |
| 耗时 | duration |
| 操作 | 详情 / 日志 / 停止 / 重试 |

### 任务详情抽屉

展示内容：

1. 任务基础信息。
2. 阶段进度。
3. 输入源。
4. 产出统计。
5. 错误摘要。
6. 运行日志。

### 接口

```http
GET  /api/projects/{projectId}/scans
GET  /api/projects/{projectId}/scans/{scanId}
GET  /api/projects/{projectId}/scans/{scanId}/logs
POST /api/projects/{projectId}/scans/{scanId}/stop
POST /api/projects/{projectId}/scans/{scanId}/retry
```

### 实时刷新

第一版建议使用轮询：

```text
RUNNING 状态任务每 3 秒刷新一次。
任务完成后停止刷新。
```

第二版可升级 WebSocket / SSE。

### 验收标准

1. 能实时看到扫描进度。
2. 失败任务能查看错误日志。
3. 支持失败任务重试。
4. 停止任务必须二次确认。

---

## 7.9 新建扫描页

### 路由

`/projects/:projectId/scans/create`

### 页面目标

选择扫描范围和扫描策略，发起一次图谱构建任务。

### 页面步骤

```text
步骤 1：选择扫描对象
步骤 2：选择扫描类型
步骤 3：配置扫描参数
步骤 4：确认并执行
```

### 步骤 1：选择扫描对象

| 对象 | 说明 |
|---|---|
| 后端仓库 | 可多选 |
| 前端仓库 | 可多选 |
| 数据库连接 | 可多选 |
| 文档资料 | 可多选 |
| 测试环境 | 可选 |

### 步骤 2：选择扫描类型

| 类型 | 说明 |
|---|---|
| 后端代码扫描 | Controller、Service、Mapper、SQL、定时任务、MQ |
| 前端代码扫描 | 路由、页面、菜单、按钮、API 调用 |
| 数据库结构扫描 | 表、字段、索引、约束、注释 |
| 文档解析 | 业务模块、流程、规则、状态 |
| 图谱构建 | 从事实生成节点和关系 |
| AI 业务归纳 | 从文档和事实生成业务图谱 |
| 测试用例生成 | 根据图谱生成测试用例 |

### 步骤 3：扫描参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| 是否增量扫描 | 是 | 根据 Git commit / 文件 hash 判断 |
| 是否启用 AI 归纳 | 是 | 文档和功能映射需要 |
| 最低入图置信度 | 0.6 | 低于该值进入待审核 |
| 是否覆盖旧图谱 | 否 | 默认保留历史版本 |
| 是否自动生成测试用例 | 否 | 第一版建议人工触发 |
| 是否扫描测试代码 | 否 | 第一版可关闭 |

### 接口

```http
POST /api/projects/{projectId}/scans
```

请求示例：

```json
{
  "scanName": "2026-06-26 全量扫描",
  "sourceIds": [1, 2, 3],
  "scanTypes": ["BACKEND_CODE", "FRONTEND_CODE", "DATABASE_SCHEMA", "DOC_PARSE", "GRAPH_BUILD"],
  "incremental": false,
  "enableAi": true,
  "minConfidence": 0.6,
  "overwriteGraph": false,
  "autoGenerateTestCase": false
}
```

### 验收标准

1. 未配置任何资料时不能创建扫描。
2. 参数确认页必须展示本次扫描影响范围。
3. 创建成功后跳转任务详情页。

---

## 7.10 图谱中心通用页面设计

图谱中心包含统一图谱、业务图谱、功能图谱、代码图谱、数据血缘、运行链路。各页面复用统一的图谱组件，只是节点类型、关系类型、默认过滤条件不同。

### 通用路由

```text
/projects/:projectId/graphs/unified
/projects/:projectId/graphs/business
/projects/:projectId/graphs/feature
/projects/:projectId/graphs/code
/projects/:projectId/graphs/data-lineage
/projects/:projectId/graphs/runtime
```

### 通用布局

```text
┌─────────────────────────────────────────────────────────────┐
│ 图谱标题 + 当前图谱版本 + 操作按钮                            │
├──────────────┬────────────────────────────────────┬─────────┤
│ 左侧筛选区    │              图谱画布               │ 右侧详情 │
│ 节点类型      │                                    │ 节点信息 │
│ 关系类型      │                                    │ 证据列表 │
│ 置信度范围    │                                    │ 测试结果 │
│ 审核状态      │                                    │ 操作按钮 │
│ 搜索框        │                                    │         │
├──────────────┴────────────────────────────────────┴─────────┤
│ 底部路径分析 / 影响分析 / 图谱统计                            │
└─────────────────────────────────────────────────────────────┘
```

### 顶部操作

| 按钮 | 说明 |
|---|---|
| 切换版本 | 查看历史图谱版本 |
| 重新布局 | 重新计算当前图谱布局 |
| 路径分析 | 选择起点和终点，计算最短路径或调用链 |
| 影响分析 | 选中节点后分析上下游影响 |
| 导出图片 | 导出当前画布 PNG / SVG |
| 导出数据 | 导出 Graph JSON / CSV |
| 全屏 | 全屏查看图谱 |

### 左侧筛选条件

| 条件 | 说明 |
|---|---|
| 节点类型 | 按 BusinessProcess、Feature、ApiEndpoint、Table 等过滤 |
| 关系类型 | 按 CALLS、READS、WRITES、IMPLEMENTED_BY 等过滤 |
| 置信度 | 0 到 1 范围 |
| 审核状态 | PENDING / APPROVED / REJECTED |
| 证据状态 | 有证据 / 无证据 |
| 测试状态 | 未测试 / 通过 / 失败 |
| 关键词 | 搜索节点名称、路径、表名、接口路径 |
| 层级深度 | 1 跳 / 2 跳 / 3 跳 / 全量 |

### 图谱节点样式

| 节点类型 | 形状 | 说明 |
|---|---|---|
| BusinessDomain | 圆角矩形 | 业务域 |
| BusinessProcess | 胶囊形 | 业务流程 |
| BusinessObject | 椭圆 | 业务对象 |
| BusinessRule | 六边形 | 业务规则 |
| FeatureModule | 圆角矩形 | 功能模块 |
| Page | 矩形 | 前端页面 |
| Button | 小圆点 | 按钮 / 操作 |
| ApiEndpoint | 矩形 | REST API |
| Controller | 矩形 | Controller 类 |
| Service | 矩形 | Service 类 / 方法 |
| Mapper | 矩形 | Mapper 接口 / XML |
| SqlStatement | 菱形 | SQL 语句 |
| Table | 数据库表图标 | 表 |
| Column | 小矩形 | 字段 |
| TestCase | 文档图标 | 测试用例 |

### 节点状态样式

| 状态 | 展示方式 |
|---|---|
| 高置信度 `>= 0.8` | 正常边框 |
| 中置信度 `0.6 - 0.8` | 虚线边框 |
| 低置信度 `< 0.6` | 警告标记 |
| 已人工确认 | 显示确认图标 |
| 已测试通过 | 显示测试通过图标 |
| 测试失败 | 显示失败图标 |
| 缺少证据 | 显示无证据图标 |

### 关系样式

| 关系 | 说明 |
|---|---|
| CONTAINS | 包含关系 |
| IMPLEMENTED_BY | 业务流程由功能实现 |
| EXPOSED_BY | 功能由页面暴露 |
| CALLS | 调用关系 |
| HANDLED_BY | API 被 Controller 处理 |
| EXECUTES | Mapper 执行 SQL |
| READS | 读取表 |
| WRITES | 写入表 |
| VERIFIED_BY | 被测试用例验证 |
| HAS_EVIDENCE | 有证据 |

### 节点点击详情抽屉

详情抽屉分为 5 个 Tab：

1. 基础信息。
2. 关联关系。
3. 证据来源。
4. 测试验证。
5. 审核记录。

#### 基础信息字段

| 字段 | 说明 |
|---|---|
| 节点 ID | nodeId |
| 节点名称 | name |
| 节点类型 | nodeType |
| 所属项目 | projectId |
| 来源类型 | CODE / DB / DOC / AI / TEST |
| 置信度 | confidence |
| 审核状态 | reviewStatus |
| 首次发现时间 | firstSeenAt |
| 最近更新时间 | updatedAt |

#### 证据来源字段

| 字段 | 说明 |
|---|---|
| 证据类型 | FILE_LINE / SQL / DB_COLUMN / DOC_PARAGRAPH / TEST_RESULT |
| 来源名称 | 文件名 / 表名 / 文档名 |
| 位置 | 行号 / 字段名 / 段落号 |
| 摘要 | 证据摘要 |
| 置信度贡献 | 该证据对节点置信度的贡献 |
| 操作 | 查看原文 / 打开代码 / 查看 SQL |

### 图谱数据结构

```ts
export interface GraphNode {
  id: string
  type: GraphNodeType
  name: string
  label: string
  confidence: number
  reviewStatus: 'PENDING' | 'APPROVED' | 'REJECTED' | 'IGNORED'
  evidenceCount: number
  testStatus?: 'NOT_TESTED' | 'PASSED' | 'FAILED' | 'PARTIAL'
  properties: Record<string, unknown>
}

export interface GraphEdge {
  id: string
  source: string
  target: string
  type: GraphEdgeType
  label: string
  confidence: number
  reviewStatus: 'PENDING' | 'APPROVED' | 'REJECTED' | 'IGNORED'
  evidenceCount: number
  properties: Record<string, unknown>
}

export interface GraphData {
  graphVersionId: string
  nodes: GraphNode[]
  edges: GraphEdge[]
  statistics: GraphStatistics
}
```

### 接口

```http
GET /api/projects/{projectId}/graphs/{graphType}
GET /api/projects/{projectId}/graphs/{graphType}/versions
GET /api/projects/{projectId}/graphs/nodes/{nodeId}
GET /api/projects/{projectId}/graphs/edges/{edgeId}
GET /api/projects/{projectId}/graphs/nodes/{nodeId}/neighbors
GET /api/projects/{projectId}/graphs/path-analysis
GET /api/projects/{projectId}/graphs/impact-analysis
```

### 图谱性能要求

第一版性能目标：

| 场景 | 要求 |
|---|---|
| 默认加载 | 不超过 1000 节点、3000 边 |
| 搜索定位 | 1 秒内定位 |
| 画布拖拽 | 无明显卡顿 |
| 节点详情 | 1 秒内打开 |
| 超大图谱 | 默认不全量展示，通过搜索和邻居展开 |

### 大图处理策略

1. 默认只加载核心节点。
2. 点击节点后按 1 跳或 2 跳展开邻居。
3. 对代码图谱按包、模块、接口分组聚合。
4. 对数据血缘按表级默认展示，字段级按需展开。
5. 对统一图谱默认隐藏 Column、Method 这类细粒度节点。

---

## 7.11 业务图谱页面

### 路由

`/projects/:projectId/graphs/business`

### 页面目标

让业务人员、产品经理、迁移负责人理解老项目的业务域、业务流程、业务对象、业务规则和状态流转。

### 默认节点类型

1. BusinessDomain。
2. BusinessProcess。
3. BusinessObject。
4. BusinessRule。
5. Role。
6. Status。
7. Feature。

### 默认关系

1. BusinessDomain CONTAINS BusinessProcess。
2. BusinessProcess USES BusinessObject。
3. BusinessProcess CONTROLLED_BY BusinessRule。
4. BusinessProcess IMPLEMENTED_BY Feature。
5. BusinessObject HAS_STATUS Status。
6. Role OPERATES BusinessProcess。

### 页面特有功能

| 功能 | 说明 |
|---|---|
| 流程模式 | 将业务流程按开始、处理中、结束状态排列 |
| 状态流转 | 选中业务对象后展示状态机 |
| 业务规则列表 | 展示规则文本、来源文档、置信度 |
| 业务到代码下钻 | 从业务流程跳转到实现该流程的功能、接口、代码 |
| 业务覆盖率 | 业务流程是否已映射到功能和测试 |

### 业务节点详情

业务流程节点详情字段：

| 字段 | 说明 |
|---|---|
| 流程名称 | 如“发起审批”、“任务派发” |
| 所属业务域 | domain |
| 参与角色 | roleList |
| 输入对象 | inputObjects |
| 输出对象 | outputObjects |
| 前置条件 | preConditions |
| 后置条件 | postConditions |
| 业务规则 | rules |
| 实现功能 | linkedFeatures |
| 关联接口 | linkedApis |
| 测试覆盖 | testCoverage |

### 验收标准

1. 业务人员可以在不看代码的情况下理解主流程。
2. 每个业务流程都能查看来源文档或代码证据。
3. 无实现功能的业务流程必须标记为风险。
4. 无测试覆盖的业务流程必须标记为待验证。

---

## 7.12 功能图谱页面

### 路由

`/projects/:projectId/graphs/feature`

### 页面目标

展示系统功能结构：模块、菜单、页面、按钮、表单、接口、权限之间的关系。

### 默认节点类型

1. FeatureModule。
2. Feature。
3. Menu。
4. Page。
5. Button。
6. FormField。
7. ApiEndpoint。
8. Permission。

### 默认关系

1. FeatureModule CONTAINS Feature。
2. Feature EXPOSED_BY Page。
3. Menu ROUTES_TO Page。
4. Page CONTAINS Button。
5. Page HAS_FIELD FormField。
6. Page CALLS ApiEndpoint。
7. Button REQUIRES Permission。
8. ApiEndpoint REQUIRES Permission。

### 页面特有功能

| 功能 | 说明 |
|---|---|
| 菜单树视图 | 左侧按照老系统菜单树展示 |
| 页面 API 调用 | 点击页面展示该页面调用的所有接口 |
| 表单字段映射 | 展示页面字段和后端 DTO / DB 字段映射 |
| 权限矩阵 | 展示页面按钮和权限编码关系 |
| 页面截图绑定 | 第二版支持上传页面截图作为证据 |

### 页面详情字段

| 字段 | 说明 |
|---|---|
| 页面名称 | pageName |
| 路由路径 | routePath |
| 组件路径 | componentPath |
| 所属菜单 | menuName |
| 页面类型 | LIST / FORM / DETAIL / DASHBOARD |
| 调用 API | apiList |
| 页面按钮 | buttonList |
| 表单字段 | fieldList |
| 权限编码 | permissionList |
| 关联业务流程 | businessProcessList |

### 验收标准

1. 能从菜单树定位到页面。
2. 能看到页面调用哪些后端接口。
3. 能看到按钮对应权限。
4. 能从页面跳转到代码图谱中的 API 和 Controller。

---

## 7.13 代码图谱页面

### 路由

`/projects/:projectId/graphs/code`

### 页面目标

展示接口到代码实现、SQL、数据库表之间的调用链，让开发人员快速理解老项目实现。

### 默认节点类型

1. ApiEndpoint。
2. Controller。
3. Method。
4. Service。
5. Mapper。
6. SqlStatement。
7. Table。
8. ExternalApi。
9. ScheduledJob。
10. MqConsumer。

### 默认关系

1. ApiEndpoint HANDLED_BY Controller。
2. Controller CALLS Service。
3. Service CALLS Service。
4. Service CALLS Mapper。
5. Mapper EXECUTES SqlStatement。
6. SqlStatement READS Table。
7. SqlStatement WRITES Table。
8. Service CALLS ExternalApi。
9. ScheduledJob CALLS Service。
10. MqConsumer CALLS Service。

### 页面特有功能

| 功能 | 说明 |
|---|---|
| 接口调用链 | `/api/x` -> Controller -> Service -> Mapper -> SQL -> Table |
| 代码证据查看 | 点击节点查看文件路径和代码行 |
| SQL 预览 | Monaco Editor 展示 SQL |
| 读写表分析 | 展示接口读取和写入的表 |
| 复杂度分析 | 接口调用层数、方法数量、SQL 数量、表数量 |
| 外部依赖分析 | 外部 HTTP、RPC、MQ、定时任务 |

### 接口详情字段

| 字段 | 说明 |
|---|---|
| API 路径 | `/api/task/list` |
| HTTP 方法 | GET / POST |
| Controller 类 | TaskController |
| Controller 方法 | list |
| 请求 DTO | TaskQueryReq |
| 响应 DTO | PageResult<TaskResp> |
| 权限注解 | `@PreAuthorize` 等 |
| 调用 Service | service methods |
| 执行 SQL | sql statements |
| 读表 | readTables |
| 写表 | writeTables |
| 关联页面 | pages |
| 关联测试用例 | testCases |

### 验收标准

1. 任意 API 能下钻到 Controller、Service、Mapper、SQL、表。
2. 任意 SQL 能查看原始 XML / 注解 SQL 位置。
3. 任意表能查看被哪些接口读写。
4. 调用链证据必须包含文件路径和行号。

---

## 7.14 数据血缘页面

### 路由

`/projects/:projectId/graphs/data-lineage`

### 页面目标

展示数据库表、字段、SQL、接口、页面、业务对象之间的数据流转关系。

### 默认节点类型

1. BusinessObject。
2. Page。
3. ApiEndpoint。
4. SqlStatement。
5. Table。
6. Column。
7. DictValue。

### 页面特有功能

| 功能 | 说明 |
|---|---|
| 表级血缘 | 默认展示表与接口、SQL 的关系 |
| 字段级血缘 | 按需展开字段与 DTO、表单字段的映射 |
| 读写方向 | 区分 READS / WRITES |
| 数据字典识别 | 展示状态字段、类型字段的取值 |
| 影响分析 | 修改某张表或字段会影响哪些接口和页面 |

### 表详情字段

| 字段 | 说明 |
|---|---|
| 表名 | tableName |
| 表注释 | tableComment |
| 主键 | primaryKey |
| 字段数 | columnCount |
| 索引 | indexes |
| 读接口数 | readApiCount |
| 写接口数 | writeApiCount |
| 关联业务对象 | businessObjectList |
| 关联页面 | pageList |

### 验收标准

1. 能从表反查接口、页面、业务流程。
2. 能从接口查看其读写表。
3. 字段级展开不影响整体性能。
4. 状态字段、字典字段必须突出展示。

---

## 7.15 运行链路页面

### 路由

`/projects/:projectId/graphs/runtime`

### 页面目标

展示非页面触发的运行链路，包括定时任务、MQ 消费者、外部接口、批处理、异步任务。

### 默认节点类型

1. ScheduledJob。
2. MqConsumer。
3. MqTopic。
4. ExternalApi。
5. Service。
6. Mapper。
7. Table。
8. Config。

### 页面特有功能

| 功能 | 说明 |
|---|---|
| 定时任务链路 | job -> service -> mapper -> table |
| MQ 消费链路 | topic -> consumer -> service -> table |
| 外部接口依赖 | service -> external api |
| 配置来源 | application.yml / nacos / env |
| 风险标记 | 未配置重试、无幂等、无事务、无测试 |

### 验收标准

1. 能识别并展示定时任务。
2. 能展示 MQ 消费者与 Topic 关系。
3. 能展示外部系统调用关系。
4. 高风险异步链路需要进入风险清单。

---

## 7.16 事实列表页

### 路由

`/projects/:projectId/facts`

### 页面目标

展示抽取阶段产生的原子事实，方便排查图谱节点为什么生成、为什么置信度低、为什么关系错误。

### 查询条件

| 字段 | 类型 | 说明 |
|---|---|---|
| 事实类型 | 下拉 | API / CLASS / METHOD / SQL / TABLE / DOC_RULE |
| 来源类型 | 下拉 | CODE / DB / DOC / AI / TEST |
| 关键词 | 输入框 | name / content |
| 置信度 | 范围 | confidence |
| 文件路径 | 输入框 | sourcePath |
| 创建时间 | 日期范围 | createdAt |

### 表格字段

| 字段 | 说明 |
|---|---|
| 事实 ID | factId |
| 事实类型 | factType |
| 事实名称 | factName |
| 来源类型 | sourceType |
| 来源位置 | 文件路径 / 表名 / 文档 |
| 摘要 | contentSummary |
| 置信度 | confidence |
| 是否已入图 | graphMapped |
| 关联节点 | nodeCount |
| 操作 | 详情 / 查看证据 / 加入审核 |

### 事实详情抽屉

Tab：

1. 原始内容。
2. 抽取结果。
3. 关联图谱节点。
4. 证据。
5. 审核记录。

### 接口

```http
GET /api/projects/{projectId}/facts
GET /api/projects/{projectId}/facts/{factId}
GET /api/projects/{projectId}/facts/{factId}/related-nodes
```

### 验收标准

1. 能按事实类型快速筛选。
2. 能看到事实原始来源。
3. 能从事实跳转到图谱节点。
4. 低置信度事实可加入审核队列。

---

## 7.17 证据检索页

### 路由

`/projects/:projectId/evidence`

### 页面目标

统一检索代码行、SQL、数据库表字段、文档段落、测试结果等证据。

### 证据类型

| 类型 | 说明 |
|---|---|
| FILE_LINE | 代码文件和行号 |
| SQL_STATEMENT | SQL 语句 |
| DB_SCHEMA | 表字段和注释 |
| DOC_PARAGRAPH | 文档段落 |
| API_DOC | 接口文档片段 |
| TEST_RESULT | 测试执行结果 |
| AI_REASONING | AI 推断说明，不能作为确定事实单独使用 |

### 页面功能

1. 关键词搜索。
2. 按证据类型筛选。
3. 按来源文件筛选。
4. 证据预览。
5. 查看关联节点和关系。
6. 打开代码 / SQL / 文档原文。

### 接口

```http
GET /api/projects/{projectId}/evidence
GET /api/projects/{projectId}/evidence/{evidenceId}
GET /api/projects/{projectId}/evidence/{evidenceId}/related
```

### 验收标准

1. 搜索结果按相关性排序。
2. 代码证据能展示上下文行。
3. 文档证据能定位到段落。
4. 测试证据能跳转到测试执行详情。

---

## 7.18 人工审核队列页

### 路由

`/projects/:projectId/reviews`

### 页面目标

对 AI 推断或低置信度图谱节点/关系进行人工确认、修正、驳回。

### 审核对象

1. 节点审核。
2. 关系审核。
3. 业务规则审核。
4. 功能映射审核。
5. 页面接口映射审核。
6. SQL 表读写关系审核。
7. 测试断言审核。

### 查询条件

| 字段 | 类型 | 说明 |
|---|---|---|
| 审核对象类型 | 下拉 | NODE / EDGE / RULE / TEST_ASSERTION |
| 图谱类型 | 下拉 | BUSINESS / FEATURE / CODE / DATA_LINEAGE |
| 优先级 | 下拉 | HIGH / MEDIUM / LOW |
| 置信度 | 范围 | confidence |
| 状态 | 下拉 | PENDING / APPROVED / REJECTED / NEED_MORE_EVIDENCE |
| 指派人 | 下拉 | assignee |

### 表格字段

| 字段 | 说明 |
|---|---|
| 审核编号 | reviewNo |
| 对象名称 | targetName |
| 对象类型 | targetType |
| 图谱类型 | graphType |
| AI 结论 | aiConclusion |
| 置信度 | confidence |
| 证据数 | evidenceCount |
| 影响范围 | impactedNodeCount |
| 优先级 | priority |
| 状态 | status |
| 操作 | 审核 / 指派 / 查看图谱 |

### 审核详情页 / 抽屉

布局：

```text
┌─────────────────────────────────────────────────────────────┐
│ 审核对象基本信息                                               │
├──────────────────────────────┬──────────────────────────────┤
│ AI 结论和推断理由              │ 证据列表                      │
├──────────────────────────────┴──────────────────────────────┤
│ 关联图谱局部视图                                               │
├─────────────────────────────────────────────────────────────┤
│ 人工处理：通过 / 驳回 / 修改 / 需要更多证据                      │
└─────────────────────────────────────────────────────────────┘
```

### 审核操作

| 操作 | 行为 |
|---|---|
| 通过 | 标记为 APPROVED，提高置信度 |
| 驳回 | 标记为 REJECTED，图谱隐藏或降低权重 |
| 修改 | 修改节点名称、类型、关系类型、属性 |
| 需要更多证据 | 标记 NEED_MORE_EVIDENCE，重新进入扫描或人工补充 |
| 合并节点 | 将重复节点合并 |
| 拆分节点 | 将错误合并的节点拆开 |

### 接口

```http
GET  /api/projects/{projectId}/reviews
GET  /api/projects/{projectId}/reviews/{reviewId}
POST /api/projects/{projectId}/reviews/{reviewId}/approve
POST /api/projects/{projectId}/reviews/{reviewId}/reject
POST /api/projects/{projectId}/reviews/{reviewId}/modify
POST /api/projects/{projectId}/reviews/{reviewId}/need-more-evidence
POST /api/projects/{projectId}/reviews/batch-approve
```

### 验收标准

1. 审核人员能看到 AI 结论和证据。
2. 审核通过后图谱节点状态实时更新。
3. 审核驳回后必须填写原因。
4. 修改操作必须保留历史记录。

---

## 7.19 测试用例页

### 路由

`/projects/:projectId/test-cases`

### 页面目标

展示和管理由图谱生成的测试用例，包括接口测试、E2E 测试、数据库断言、业务规则断言。

### 查询条件

| 字段 | 类型 | 说明 |
|---|---|---|
| 用例类型 | 下拉 | API / E2E / DB_ASSERTION / BUSINESS_RULE |
| 来源图谱 | 下拉 | BUSINESS / FEATURE / CODE |
| 关联节点 | 输入框 | nodeName |
| 状态 | 下拉 | DRAFT / CONFIRMED / DISABLED |
| 最近结果 | 下拉 | NOT_RUN / PASSED / FAILED |
| 生成方式 | 下拉 | AI_GENERATED / MANUAL |

### 表格字段

| 字段 | 说明 |
|---|---|
| 用例编号 | caseNo |
| 用例名称 | caseName |
| 用例类型 | caseType |
| 关联功能 | featureName |
| 关联 API | apiPath |
| 断言数量 | assertionCount |
| 生成方式 | generateType |
| 状态 | status |
| 最近执行结果 | lastRunStatus |
| 最近执行时间 | lastRunTime |
| 操作 | 编辑 / 复制 / 执行 / 禁用 / 查看结果 |

### 测试用例编辑器

Tab：

1. 基础信息。
2. 请求配置。
3. 前置步骤。
4. 断言配置。
5. 数据库断言。
6. 关联图谱。

#### 基础信息

| 字段 | 说明 |
|---|---|
| 用例名称 | caseName |
| 用例类型 | API / E2E / DB_ASSERTION |
| 优先级 | P0 / P1 / P2 |
| 关联功能 | featureId |
| 关联 API | apiNodeId |
| 说明 | description |

#### API 请求配置

| 字段 | 说明 |
|---|---|
| 请求方法 | GET / POST / PUT / DELETE |
| 请求路径 | `/api/task/list` |
| Headers | JSON |
| Query 参数 | JSON |
| Body | JSON |
| 认证方式 | 使用测试环境 Token / 自定义 Header |

#### 断言配置

| 类型 | 示例 |
|---|---|
| 状态码断言 | `status == 200` |
| JSON 字段断言 | `$.code == 0` |
| 非空断言 | `$.data.records` 不为空 |
| 数量断言 | `$.data.total >= 0` |
| 数据库断言 | 表记录新增 / 状态变更 |
| 业务规则断言 | 状态不允许非法流转 |

### 接口

```http
GET  /api/projects/{projectId}/test-cases
POST /api/projects/{projectId}/test-cases/generate
POST /api/projects/{projectId}/test-cases
PUT  /api/projects/{projectId}/test-cases/{caseId}
GET  /api/projects/{projectId}/test-cases/{caseId}
DELETE /api/projects/{projectId}/test-cases/{caseId}
POST /api/projects/{projectId}/test-cases/{caseId}/run
```

### 验收标准

1. AI 生成的测试用例默认是 DRAFT，必须人工确认后才能批量执行。
2. 用例能展示来源图谱节点。
3. 用例断言可以编辑。
4. 执行结果能回写到图谱验证状态。

---

## 7.20 测试执行页

### 路由

`/projects/:projectId/test-runs`

### 页面目标

执行测试用例，查看测试进度、日志、断言结果和失败原因。

### 表格字段

| 字段 | 说明 |
|---|---|
| 执行编号 | runNo |
| 执行名称 | runName |
| 环境 | envName |
| 用例数量 | caseCount |
| 通过数 | passedCount |
| 失败数 | failedCount |
| 跳过数 | skippedCount |
| 状态 | WAITING / RUNNING / PASSED / FAILED / CANCELED |
| 开始时间 | startTime |
| 耗时 | duration |
| 操作 | 详情 / 日志 / 停止 / 重新执行 |

### 新建执行弹窗

| 字段 | 必填 | 说明 |
|---|---|---|
| 执行名称 | 是 | runName |
| 测试环境 | 是 | testEnvId |
| 用例范围 | 是 | 全部 / 按功能 / 按 API / 手动选择 |
| 是否回写图谱 | 是 | 默认是 |
| 失败是否继续 | 是 | 默认继续 |

### 执行详情页

Tab：

1. 总览。
2. 用例结果。
3. 断言结果。
4. 执行日志。
5. 图谱回写。

### 接口

```http
GET  /api/projects/{projectId}/test-runs
POST /api/projects/{projectId}/test-runs
GET  /api/projects/{projectId}/test-runs/{runId}
GET  /api/projects/{projectId}/test-runs/{runId}/case-results
GET  /api/projects/{projectId}/test-runs/{runId}/logs
POST /api/projects/{projectId}/test-runs/{runId}/stop
POST /api/projects/{projectId}/test-runs/{runId}/rerun
```

### 验收标准

1. 执行中能实时看到进度。
2. 失败用例能看到请求、响应、断言失败原因。
3. 数据库断言失败能看到预期值和实际值。
4. 执行完成后能跳转验证报告。

---

## 7.21 验证报告页

### 路由

`/projects/:projectId/reports/validation`

### 页面目标

展示图谱正确性验证结果，用于判断当前图谱能否支撑老项目理解和迁移。

### 核心指标

| 指标 | 说明 |
|---|---|
| 图谱节点总数 | 当前版本节点数量 |
| 图谱关系总数 | 当前版本关系数量 |
| 已验证节点数 | 人工确认或测试验证通过 |
| 已验证关系数 | 人工确认或测试验证通过 |
| 测试覆盖节点数 | 有测试用例覆盖的节点 |
| 测试覆盖关系数 | 有测试用例覆盖的关系 |
| 验证通过率 | 通过验证 / 已验证 |
| 高风险节点数 | 低置信度、缺证据、测试失败 |
| 迁移可用度 | 综合评分 |

### 报告分区

1. 总体评分。
2. 图谱覆盖率。
3. 按图谱类型统计。
4. 测试执行结果。
5. 人工审核结果。
6. 风险节点和风险关系。
7. 缺失证据清单。
8. 建议下一步动作。

### 风险清单字段

| 字段 | 说明 |
|---|---|
| 风险对象 | 节点 / 关系名称 |
| 对象类型 | API / SQL / BusinessProcess |
| 风险类型 | LOW_CONFIDENCE / NO_EVIDENCE / TEST_FAILED / NO_MAPPING |
| 严重级别 | HIGH / MEDIUM / LOW |
| 影响范围 | 关联节点数、功能数 |
| 建议动作 | 补充文档 / 人工审核 / 增加测试 / 重新扫描 |

### 接口

```http
GET /api/projects/{projectId}/reports/validation/summary
GET /api/projects/{projectId}/reports/validation/coverage
GET /api/projects/{projectId}/reports/validation/risks
GET /api/projects/{projectId}/reports/validation/suggestions
GET /api/projects/{projectId}/reports/validation/export
```

### 验收标准

1. 能清楚判断图谱是否可信。
2. 每个风险项能跳转到对应节点、证据或测试结果。
3. 支持导出 Markdown / PDF / Excel。
4. 报告能作为迁移评审材料。

---

## 7.22 迁移风险页

### 路由

`/projects/:projectId/migration/risks`

### 页面目标

基于图谱复杂度、依赖关系、测试验证结果，识别迁移风险。

### 风险类型

| 风险类型 | 判断依据 |
|---|---|
| 复杂调用链 | API 调用层级过深、涉及多个 Service / Mapper |
| 表强耦合 | 一个功能读写大量表 |
| 文档缺失 | 业务流程只有代码证据，无产品文档 |
| 测试缺失 | 核心接口无测试用例覆盖 |
| 外部依赖 | 依赖外部系统、MQ、定时任务 |
| 低置信度 | 关键关系 confidence < 0.6 |
| 数据血缘不清 | 表字段来源或去向无法确认 |
| 权限不清 | 页面、按钮、接口权限映射缺失 |

### 表格字段

| 字段 | 说明 |
|---|---|
| 风险编号 | riskNo |
| 风险名称 | riskName |
| 风险类型 | riskType |
| 关联模块 | moduleName |
| 关联功能 | featureName |
| 严重级别 | severity |
| 影响范围 | impactedCount |
| 证据数 | evidenceCount |
| 建议处理 | suggestion |
| 状态 | OPEN / PROCESSING / CLOSED |
| 操作 | 查看图谱 / 创建审核 / 创建测试 |

### 验收标准

1. 风险来源必须可追溯。
2. 风险可关联到审核任务或测试用例。
3. 支持按模块导出风险清单。

---

## 8. 通用组件详细设计

## 8.1 ConfidenceBadge 置信度组件

### 用途

统一展示节点、关系、事实、测试断言的置信度。

### Props

```ts
interface ConfidenceBadgeProps {
  value: number
  showText?: boolean
  size?: 'small' | 'default' | 'large'
}
```

### 展示规则

| 范围 | 文案 | 状态 |
|---|---|---|
| `>= 0.8` | 高 | success |
| `0.6 - 0.8` | 中 | warning |
| `< 0.6` | 低 | error |

---

## 8.2 EvidencePanel 证据面板

### 用途

展示节点、关系、事实、测试用例的证据列表。

### 功能

1. 证据分组。
2. 代码证据预览。
3. SQL 证据预览。
4. 文档段落预览。
5. 测试结果预览。
6. 跳转原始来源。

### Props

```ts
interface EvidencePanelProps {
  targetType: 'NODE' | 'EDGE' | 'FACT' | 'TEST_CASE'
  targetId: string
  readonly?: boolean
}
```

---

## 8.3 CodeViewer 代码查看器

### 用途

展示 Java、XML、SQL、YAML、JSON、TypeScript 等代码证据。

### 功能

1. 语法高亮。
2. 行号展示。
3. 高亮证据行。
4. 复制代码。
5. 展开上下文。

### Props

```ts
interface CodeViewerProps {
  language: 'java' | 'xml' | 'sql' | 'yaml' | 'json' | 'typescript' | 'text'
  content: string
  highlightLines?: number[]
  readonly?: boolean
}
```

---

## 8.4 GraphCanvas 图谱画布

### 用途

所有图谱页面的核心组件。

### Props

```ts
interface GraphCanvasProps {
  graphType: 'UNIFIED' | 'BUSINESS' | 'FEATURE' | 'CODE' | 'DATA_LINEAGE' | 'RUNTIME'
  graphData: GraphData
  selectedNodeId?: string
  layout?: 'force' | 'dagre' | 'radial' | 'grid' | 'combo'
  readonly?: boolean
}
```

### Emits

```ts
interface GraphCanvasEmits {
  nodeClick: [node: GraphNode]
  edgeClick: [edge: GraphEdge]
  canvasClick: []
  expandNode: [nodeId: string]
  pathAnalyze: [sourceId: string, targetId: string]
}
```

### 内部能力

1. 根据节点类型渲染不同形状。
2. 根据置信度渲染不同状态。
3. 支持节点搜索定位。
4. 支持框选节点。
5. 支持一跳展开。
6. 支持收起子图。
7. 支持导出图片。
8. 支持高亮调用链。

---

## 8.5 TestCaseEditor 测试用例编辑器

### 用途

创建和编辑 API / E2E / DB 断言测试用例。

### 功能

1. 基础信息编辑。
2. HTTP 请求编辑。
3. JSON Body 编辑。
4. 断言规则编辑。
5. 数据库断言编辑。
6. 关联图谱节点查看。
7. 测试运行预览。

---

## 9. 前端接口契约

## 9.1 通用响应结构

```ts
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  traceId?: string
  timestamp: string
}
```

## 9.2 分页响应结构

```ts
export interface PageResult<T> {
  records: T[]
  total: number
  pageNo: number
  pageSize: number
  pages: number
}
```

## 9.3 错误码约定

| 错误码 | 说明 | 前端处理 |
|---|---|---|
| 0 | 成功 | 正常处理 |
| 400 | 参数错误 | 表单或消息提示 |
| 401 | 未登录 | 跳转登录 |
| 403 | 无权限 | 展示无权限页 |
| 404 | 资源不存在 | 展示空状态或 404 页 |
| 409 | 状态冲突 | 提示用户刷新 |
| 500 | 系统异常 | 错误提示 + traceId |

## 9.4 请求封装要求

`src/api/request.ts` 统一处理：

1. Token 注入。
2. 请求超时。
3. 错误码处理。
4. 401 自动跳转登录。
5. 下载文件处理。
6. traceId 显示。
7. 防重复提交。

---

## 10. 状态管理设计

## 10.1 userStore

```ts
interface UserState {
  accessToken: string
  refreshToken: string
  userInfo: UserInfo | null
  permissions: string[]
}
```

职责：

1. 保存登录态。
2. 保存权限。
3. 判断按钮权限。
4. 退出登录。

## 10.2 projectStore

```ts
interface ProjectState {
  currentProjectId?: string
  currentProject?: ProjectInfo
  projectList: ProjectInfo[]
}
```

职责：

1. 当前项目切换。
2. 项目基础信息缓存。
3. 项目上下文校验。

## 10.3 graphStore

```ts
interface GraphState {
  graphType: GraphType
  graphVersionId?: string
  graphData?: GraphData
  selectedNode?: GraphNode
  selectedEdge?: GraphEdge
  filters: GraphFilter
}
```

职责：

1. 当前图谱缓存。
2. 当前选中节点 / 边。
3. 图谱筛选条件。
4. 图谱局部展开数据。

## 10.4 taskStore

```ts
interface TaskState {
  runningTasks: ScanTask[]
  pollingTaskIds: string[]
}
```

职责：

1. 维护运行中任务。
2. 控制轮询。
3. 全局任务通知。

## 10.5 testStore

```ts
interface TestState {
  selectedCaseIds: string[]
  currentRun?: TestRun
  runProgress?: TestRunProgress
}
```

职责：

1. 测试用例选择。
2. 测试执行进度。
3. 测试结果缓存。

---

## 11. 权限与安全设计

### 11.1 前端权限控制

前端权限分三层：

1. 路由权限：无权限不允许进入页面。
2. 菜单权限：无权限不展示菜单。
3. 按钮权限：无权限不展示按钮或禁用按钮。

示例：

```ts
export function hasPermission(permission: string): boolean {
  const userStore = useUserStore()
  return userStore.permissions.includes(permission)
}
```

### 11.2 敏感信息处理

| 信息 | 处理方式 |
|---|---|
| Git Token | 不回显、不打印日志 |
| 数据库密码 | 不回显、不打印日志 |
| SSH Key | 不回显、不打印日志 |
| 测试环境 Token | 用掩码展示 |
| 代码内容 | 按项目权限控制 |
| 文档内容 | 按项目权限控制 |

### 11.3 XSS 防护

1. 文档预览、Markdown 预览必须进行 HTML sanitize。
2. AI 输出内容不能直接 `v-html`。
3. 代码预览以纯文本方式传入 Monaco。
4. 下载文件名必须后端过滤。

---

## 12. 前端性能设计

### 12.1 页面级性能

| 页面 | 优化策略 |
|---|---|
| 图谱页面 | 默认加载核心图，按需展开邻居 |
| 事实列表 | 分页查询，避免全量加载 |
| 证据检索 | 服务端搜索，前端只展示结果 |
| 测试日志 | 分段加载，避免一次性加载大日志 |
| 文档预览 | 分页或按章节加载 |

### 12.2 图谱性能

1. 超过 1000 节点时提示用户使用过滤器。
2. 默认隐藏字段级节点。
3. 支持节点聚合。
4. 支持局部展开。
5. 布局计算放在 Web Worker 中，第二版实现。
6. 大图导出由后端完成，第二版实现。

### 12.3 构建性能

1. 路由懒加载。
2. 图谱组件独立 chunk。
3. Monaco Editor 独立 chunk。
4. ECharts 独立按需加载。
5. Ant Design Vue 按需引入。

---

## 13. 前端测试设计

### 13.1 单元测试

使用 Vitest 覆盖：

1. 权限函数。
2. 时间格式化。
3. 图谱节点样式计算。
4. 置信度组件。
5. API 响应处理。
6. 表单校验函数。

### 13.2 组件测试

重点组件：

1. ConfidenceBadge。
2. EvidencePanel。
3. GraphToolbar。
4. TestCaseEditor。
5. ReviewActionPanel。
6. CodeViewer。

### 13.3 E2E 测试

使用 Playwright 覆盖主流程：

1. 登录。
2. 新建项目。
3. 配置代码仓库。
4. 配置数据库。
5. 上传文档。
6. 新建扫描任务。
7. 查看图谱。
8. 审核低置信度节点。
9. 生成测试用例。
10. 执行测试。
11. 查看验证报告。

---

## 14. 前端开发任务拆解

### 14.1 第一阶段：基础框架，1 周

| 任务 | 产出 |
|---|---|
| 初始化 Vite + Vue + TypeScript 工程 | 前端基础项目 |
| 接入 Ant Design Vue | UI 基础能力 |
| 配置 Router / Pinia / Axios | 路由、状态、请求 |
| 实现 BasicLayout | 基础布局 |
| 实现登录和权限守卫 | 登录闭环 |
| 实现项目选择器 | 项目上下文 |

### 14.2 第二阶段：项目和资料接入，1 周

| 任务 | 产出 |
|---|---|
| 项目列表和项目详情 | 项目管理页面 |
| 代码仓库接入页 | Git 配置页面 |
| 数据库连接页 | DB 配置页面 |
| 文档资料页 | 文档上传和预览基础 |
| 扫描任务列表页 | 任务查询和日志查看 |
| 新建扫描页 | 扫描任务创建向导 |

### 14.3 第三阶段：图谱中心，2 周

| 任务 | 产出 |
|---|---|
| GraphCanvas 基础渲染 | 图谱画布 |
| GraphToolbar | 过滤、搜索、布局 |
| NodeDetailDrawer | 节点详情抽屉 |
| EdgeDetailDrawer | 关系详情抽屉 |
| EvidencePanel | 证据展示 |
| 业务图谱页面 | 业务视图 |
| 功能图谱页面 | 功能视图 |
| 代码图谱页面 | 代码视图 |
| 数据血缘页面 | 数据视图 |
| 运行链路页面 | 运行视图 |

### 14.4 第四阶段：审核和测试，1.5 周

| 任务 | 产出 |
|---|---|
| 事实列表页 | 原子事实管理 |
| 证据检索页 | 证据查询 |
| 人工审核队列 | 审核闭环 |
| 测试用例页 | 用例管理 |
| 测试执行页 | 执行管理 |
| 验证报告页 | 图谱验证报告 |

### 14.5 第五阶段：优化和验收，0.5 周

| 任务 | 产出 |
|---|---|
| 权限细化 | 菜单、按钮权限 |
| 性能优化 | 大图加载优化 |
| 错误处理 | 统一异常和空状态 |
| E2E 测试 | 主流程测试 |
| 文档整理 | 前端 README 和部署说明 |

---

## 15. MVP 页面优先级

### P0 必做

1. 登录页。
2. 项目列表页。
3. 项目详情页。
4. 代码仓库接入页。
5. 数据库连接页。
6. 文档资料页。
7. 扫描任务列表页。
8. 新建扫描页。
9. 统一图谱页面。
10. 代码图谱页面。
11. 功能图谱页面。
12. 节点详情抽屉。
13. 证据面板。
14. 人工审核队列。
15. 测试用例页。
16. 测试执行页。
17. 验证报告页。

### P1 建议做

1. 业务图谱页面。
2. 数据血缘页面。
3. 运行链路页面。
4. 迁移风险页。
5. 全局搜索。
6. 图谱路径分析。
7. 图谱影响分析。
8. 测试结果回写可视化。

### P2 后续增强

1. 页面截图证据绑定。
2. 图谱版本对比。
3. 大图 Web Worker 布局。
4. 多项目图谱对比。
5. 迁移任务看板。
6. 自动生成迁移方案。
7. 与 GitLab / Jenkins / Kubernetes 集成。

---

## 16. 前后端联调计划

### 16.1 Mock 阶段

前端先用 Mock 数据开发以下模块：

1. 项目列表。
2. 扫描任务。
3. 图谱数据。
4. 节点详情。
5. 证据列表。
6. 审核队列。
7. 测试用例。
8. 验证报告。

### 16.2 联调顺序

| 顺序 | 模块 | 后端接口依赖 |
|---|---|---|
| 1 | 登录和权限 | auth API |
| 2 | 项目管理 | project API |
| 3 | 资料接入 | source API |
| 4 | 扫描任务 | scan API |
| 5 | 图谱展示 | graph API |
| 6 | 事实和证据 | fact / evidence API |
| 7 | 人工审核 | review API |
| 8 | 测试用例 | test-case API |
| 9 | 测试执行 | test-run API |
| 10 | 验证报告 | report API |

### 16.3 接口变更约束

1. 所有列表接口必须支持分页。
2. 所有详情接口必须返回 `updatedAt`。
3. 所有异步任务必须返回任务 ID。
4. 所有错误必须返回 `traceId`。
5. 图谱接口必须支持按深度和节点类型过滤。

---

## 17. 验收标准汇总

### 17.1 功能验收

1. 能创建项目并配置代码仓库、数据库、文档。
2. 能发起扫描任务并查看任务日志。
3. 能查看统一图谱、功能图谱、代码图谱。
4. 能点击节点查看证据。
5. 能人工审核低置信度节点和关系。
6. 能生成测试用例。
7. 能执行测试并查看断言结果。
8. 能查看图谱验证报告。

### 17.2 质量验收

1. 图谱默认加载时间小于 5 秒。
2. 普通列表页面加载时间小于 3 秒。
3. 关键页面无控制台错误。
4. 前端 TypeScript 无类型错误。
5. 核心组件有单元测试。
6. 主流程有 E2E 测试。

### 17.3 可用性验收

1. 非技术人员能查看业务图谱。
2. 开发人员能通过代码图谱定位接口实现。
3. 测试人员能通过测试页面执行和查看结果。
4. 迁移负责人能通过验证报告判断风险。

---

## 18. 风险与应对

| 风险 | 表现 | 应对 |
|---|---|---|
| 图谱节点太多 | 页面卡顿、用户看不懂 | 默认聚合、按需展开、分层展示 |
| 证据链太复杂 | 用户不知道结论从哪来 | 节点详情中固定证据 Tab |
| AI 结论不可信 | 业务人员不敢使用 | 置信度 + 人工审核 + 测试验证 |
| 接口返回慢 | 图谱加载慢 | 后端分页 / 局部图 / 缓存 |
| 前端组件复杂 | 后期难维护 | 图谱组件、审核组件、测试组件分层封装 |
| 权限复杂 | 数据泄露 | 路由、菜单、按钮、接口四层控制 |

---

## 19. 附录：前端依赖建议

```json
{
  "dependencies": {
    "@antv/g6": "^5.0.0",
    "ant-design-vue": "^4.0.0",
    "axios": "^1.0.0",
    "dayjs": "^1.11.0",
    "echarts": "^6.0.0",
    "lodash-es": "^4.17.0",
    "monaco-editor": "^0.50.0",
    "pinia": "^3.0.0",
    "vue": "^3.0.0",
    "vue-router": "^4.0.0"
  },
  "devDependencies": {
    "@playwright/test": "latest",
    "@vitejs/plugin-vue": "latest",
    "eslint": "latest",
    "prettier": "latest",
    "typescript": "^5.0.0",
    "vite": "^8.0.0",
    "vitest": "latest"
  }
}
```

> 注意：企业落地时不要长期使用 `latest`，应在初次搭建时锁定具体版本，并通过 Renovate / Dependabot 定期升级。

---

## 20. 附录：核心接口清单

```text
认证：
POST /api/auth/login
POST /api/auth/logout
GET  /api/auth/me

项目：
GET    /api/projects
POST   /api/projects
GET    /api/projects/{projectId}
PUT    /api/projects/{projectId}
DELETE /api/projects/{projectId}

资料：
GET  /api/projects/{projectId}/sources/repos
POST /api/projects/{projectId}/sources/repos
POST /api/projects/{projectId}/sources/repos/{repoId}/pull

GET  /api/projects/{projectId}/sources/databases
POST /api/projects/{projectId}/sources/databases
POST /api/projects/{projectId}/sources/databases/{dbId}/scan-schema

GET  /api/projects/{projectId}/sources/docs
POST /api/projects/{projectId}/sources/docs/upload
POST /api/projects/{projectId}/sources/docs/{docId}/parse

扫描：
GET  /api/projects/{projectId}/scans
POST /api/projects/{projectId}/scans
GET  /api/projects/{projectId}/scans/{scanId}
GET  /api/projects/{projectId}/scans/{scanId}/logs
POST /api/projects/{projectId}/scans/{scanId}/stop

图谱：
GET /api/projects/{projectId}/graphs/{graphType}
GET /api/projects/{projectId}/graphs/nodes/{nodeId}
GET /api/projects/{projectId}/graphs/edges/{edgeId}
GET /api/projects/{projectId}/graphs/nodes/{nodeId}/neighbors
GET /api/projects/{projectId}/graphs/path-analysis
GET /api/projects/{projectId}/graphs/impact-analysis

事实和证据：
GET /api/projects/{projectId}/facts
GET /api/projects/{projectId}/facts/{factId}
GET /api/projects/{projectId}/evidence
GET /api/projects/{projectId}/evidence/{evidenceId}

审核：
GET  /api/projects/{projectId}/reviews
GET  /api/projects/{projectId}/reviews/{reviewId}
POST /api/projects/{projectId}/reviews/{reviewId}/approve
POST /api/projects/{projectId}/reviews/{reviewId}/reject
POST /api/projects/{projectId}/reviews/{reviewId}/modify

测试：
GET  /api/projects/{projectId}/test-cases
POST /api/projects/{projectId}/test-cases/generate
POST /api/projects/{projectId}/test-cases/{caseId}/run

GET  /api/projects/{projectId}/test-runs
POST /api/projects/{projectId}/test-runs
GET  /api/projects/{projectId}/test-runs/{runId}
GET  /api/projects/{projectId}/test-runs/{runId}/logs

报告：
GET /api/projects/{projectId}/reports/validation/summary
GET /api/projects/{projectId}/reports/validation/coverage
GET /api/projects/{projectId}/reports/validation/risks
GET /api/projects/{projectId}/reports/validation/export
```

---

## 21. 参考资料

1. Vue 官方文档：`https://vuejs.org/guide/introduction.html`
2. Vite 官方文档：`https://vite.dev/guide/`
3. Ant Design Vue 官方文档：`https://antdv.com/docs/vue/introduce`
4. AntV G6 官方文档：`https://g6.antv.antgroup.com/manual/introduction`
5. Node.js Release 官方页面：`https://nodejs.org/en/about/previous-releases`

