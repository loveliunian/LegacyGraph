# LegacyGraph 前端统一整合与重新设计计划

## 一、概述

本计划针对 LegacyGraph AI 图谱理解平台的前端界面进行统一设计整合。项目当前拥有 60+ 页面/视图，存在设计风格不统一、布局模式混乱、硬编码颜色散布等问题。本计划将通过输出 `.design` 画布项目（HTML + Tailwind CDN）的方式，为后续实际代码改造提供可执行的视觉参考和规范锚点。

**核心目标：**
- 建立统一的设计语言和视觉规范
- 定义 4 种标准页面布局模式，覆盖所有 60+ 页面
- 消除硬编码颜色（331 处 Element Plus 旧色值、7 处旧渐变色、48 处 Element Plus 默认蓝色 #409EFF）
- 统一间距系统、排版层级、组件样式
- 输出约 12 个核心页面设计稿，作为全量改造的视觉基准

**技术约束：**
- 设备类型：Desktop（后台管理系统）
- 品牌色系保持不变：主色 #6366F1（Indigo），沿用 `variables.css` 中定义的完整色板
- 支持浅色/深色双主题
- 不涉及后端代码修改
- 设计稿使用 Tailwind CDN + Element Plus CDN，静态 HTML 文件

---

## 二、现状深度分析

### 2.1 硬编码颜色问题

**Element Plus 旧色值（331 处，涉及 51 个文件）：**
- `#303133`（旧 primary text）-- 约 30+ 个文件
- `#909399`（旧 secondary text）
- `#606266`（旧 regular text）
- `#f5f7fa`（旧 fill 背景）
- `#e4e7ed`（旧边框色）
- `#c0c4cc`（旧 disabled/placeholder 色）

**旧渐变色（7 处，跨越 6 个文件）：**
- `linear-gradient(135deg, #667eea 0%, #764ba2 100%)` -- 登录页、Dashboard 数据源图标等

**Element Plus 默认蓝色（48 处，涉及 18 个文件）：**
- `#409eff` 作为默认主色出现在多个组件中，与品牌色 #6366F1 不一致

### 2.2 布局模式混乱

当前存在至少 5 种不同的页面布局模式：

| 模式 | 使用页面 | 特征 |
|------|---------|------|
| el-row/el-col 栅格 | Dashboard | 嵌套 el-row + el-col，20px padding |
| el-card 包裹 | ProjectList, UserList | 直接 div + el-card，无外层 padding |
| el-container 侧边栏 | ProjectDetail | 220px 固定侧边栏 + 60px header + 24px padding |
| Tab 布局 | SystemSettingsLayout, EvidenceWorkbench | 20px 外层 padding + el-tabs |
| 自定义 page-header | ScanTaskList, UnifiedGraph | 自定义 div.page-header + h3 |

### 2.3 其他问题

- **页面头部风格各异**：有的用 el-card header，有的用自定义 div，有的无标题
- **搜索区域不统一**：有的用 SearchForm 组件，有的手写 el-form
- **空状态处理不统一**：el-empty、自定义文字、EmptyState 组件混用
- **间距体系混乱**：16px/20px/24px padding 混用
- **品牌字体未落地**：variables.css 定义了 DM Sans/Serif/Mono，但绝大多数页面未使用

---

## 三、设计方案：统一设计语言

### 3.1 设计令牌体系

**间距系统（8px 基准网格）：**

| 令牌 | 值 | 用途 |
|------|-----|------|
| --space-2 | 8px | 元素内间距 |
| --space-3 | 12px | 小组件间距 |
| --space-4 | 16px | 卡片内间距、Tab 内容 |
| --space-5 | 20px | 页面级 padding |
| --space-6 | 24px | 主内容区 padding（统一标准） |
| --space-8 | 32px | 大区域分隔 |

**统一决策：**
- 页面外层 padding 统一为 24px
- 卡片内间距统一为 20px
- 卡片之间间距统一为 16px

**圆角系统：**
- --radius-sm: 6px（Tag、Badge）
- --radius-md: 8px（卡片、输入框）
- --radius-lg: 12px（大面板、对话框）

**字体排版层级：**

| 层级 | 字体 | 字号 | 字重 |
|------|------|------|------|
| Display | DM Serif Display | 36px | 400 |
| H1 | DM Sans | 24px | 700 |
| H2 | DM Sans | 20px | 600 |
| H3 | DM Sans | 16px | 600 |
| Body | DM Sans | 14px | 400 |
| Caption | DM Sans | 12px | 400 |
| Mono | JetBrains Mono | 13px | 400 |

**状态颜色映射（统一所有页面）：**

| 状态 | 颜色变量 | Tag Type |
|------|---------|----------|
| 成功/已确认/通过/活跃 | --el-color-success (#14B8A6) | success |
| 警告/待处理/待审核 | --el-color-warning (#F59E0B) | warning |
| 危险/失败/已驳回 | --el-color-danger (#EF4444) | danger |
| 进行中/处理中 | --el-color-primary (#6366F1) | primary |
| 信息/已取消/跳过 | --el-color-info (#909399) | info |

### 3.2 四种标准页面布局模式

**模式 A -- 全宽内容页（Dashboard）**
- 无侧边栏，页面 padding: 24px
- 标题区: PageHeader（H2 + 可选描述 + 操作按钮）
- 内容区: CSS Grid 布局

**模式 B -- 列表页（ProjectList 等一级列表）**
- 无侧边栏，页面 padding: 24px
- 搜索区: PageHeader + SearchForm
- 内容区: BaseTable + 分页
- 卡片: shadow="never" + border

**模式 C -- 侧边栏 + 内容区（ProjectDetail）**
- 左侧 240px 侧边栏（从 220px 调整）
- 右侧内容区 padding: 24px
- 面包屑集成到内容区顶部

**模式 D -- Tab 页（SystemSettings、Workbench）**
- 页面 padding: 24px，标题区 H2
- el-tabs 切换子页面
- 每个 tab-pane 内按模式 B 组织

---

## 四、分阶段变更方案

### 阶段 1：设计系统基础稿

**输出：** .design 画布项目 -- 设计系统基础页面

**具体内容：**
1. **Color Palette 色板展示** -- 主色全色阶、语义色、中性色、背景色、边框色，浅色/深色对比
2. **Typography 排版体系** -- Display/H1-H3/Body/Caption/Mono 各层级实际渲染
3. **Spacing Grid** -- 8px 栅格可视化、间距令牌对照
4. **Component Library** -- PageHeader（3 种变体）、SearchForm、StatCard、StatusTag、EmptyState、PageContainer

**为什么：** 所有后续页面设计稿依赖此文件定义的令牌和组件规范。

---

### 阶段 2：全局布局框架稿

**输出：** .design 画布项目 -- 4 个布局页面

#### 2.1 登录页

**当前问题：**
- 背景使用旧渐变 `#667eea -> #764ba2`（与品牌色 #6366F1 不一致）
- 多处硬编码颜色（#303133, #909399, #ebeef5, #c0c4cc）

**重新设计：**
- 背景：改为品牌渐变 `linear-gradient(135deg, #6366F1, #8B5CF6, #A78BFA)`（Indigo -> Violet）
- 登录卡片：shadow-dark，圆角 12px，backdrop-filter 模糊
- 表单：label 改为顶部对齐，主按钮 height 44px
- 增加品牌装饰元素（抽象图谱图形）
- 深色模式适配

#### 2.2 AppLayout 顶部导航

**重新设计：**
- Header 高度统一 56px
- Logo: SVG + "LegacyGraph"（font-weight 700, font-size 16px）
- 菜单: hover/active 样式使用 CSS 变量
- 右侧操作区: 图标按钮 36px x 36px

#### 2.3 SystemSettingsLayout 系统管理布局

**重新设计：**
- 外层 padding: 20px -> 24px
- 标题区: h2 20px
- Tab 样式优化

#### 2.4 ProjectDetailLayout 项目详情布局

**当前问题：**
- 侧边栏 220px 偏窄，硬编码背景/边框色
- 独立 60px header 占用垂直空间

**重新设计：**
- 侧边栏: 240px，背景/边框使用 CSS 变量
- 移除独立 header，面包屑集成到内容区顶部 PageHeader
- 菜单 active 样式使用 CSS 变量

---

### 阶段 3：核心页面设计稿

**输出：** .design 画布项目 -- 8~10 个核心页面

| 页面 | 对应模式 | 关键改动 |
|------|---------|---------|
| Dashboard 工作台 | 模式 A | el-row/col -> CSS Grid，所有硬编码色替换，数据源图标渐变更新 |
| ProjectList 项目列表 | 模式 B | 新增 PageContainer/PageHeader/SearchForm，卡片 shadow="never" |
| ProjectOverview 项目概览 | 模式 C 内 | StatCard 图标渐变更新，颜色变量替换 |
| ScanTaskList 扫描任务 | 模式 C+B | PageHeader 替代自定义 header，StatusTag 统一 |
| UnifiedGraph 统一图谱 | 模式 C | PageHeader 替代自定义 header，统计条/工具栏颜色替换 |
| EvidenceWorkbench 证据工作台 | 模式 D | padding 16->24px，PageHeader 替代自定义 header |
| GraphQa QA 问答 | 模式 C | 14 处硬编码色全部替换为 CSS 变量 |
| UserList 用户管理 | 模式 D+B | 确认标准目标状态，SearchForm 背景色变量替换 |
| Error 404/403 | -- | 403 与已较好的 404 统一风格 |
| AgentHub AI 助手 | 模式 C | Agent 卡片布局，品牌色系 |

---

### 阶段 4：颜色迁移对照稿

**输出：** .design 画布项目 -- 颜色迁移对照手册

展示完整映射表：

| 旧色值 | 新 CSS 变量 | 语义 |
|--------|------------|------|
| #303133 | var(--el-text-color-primary) | 主要文字 |
| #606266 | var(--el-text-color-regular) | 常规文字 |
| #909399 | var(--el-text-color-secondary) | 次要文字 |
| #c0c4cc | var(--el-text-color-placeholder) | 占位文字 |
| #f5f7fa | var(--el-fill-color) | 填充背景 |
| #e4e7ed | var(--el-border-color-light) | 浅边框 |
| #ebeef5 | var(--el-border-color-lighter) | 更浅边框 |
| #409eff | var(--el-color-primary) | 主色 |
| #667eea->#764ba2 | #6366F1->#8B5CF6 | 品牌渐变 |

每个映射提供颜色色块对比 + 使用场景示例。

---

## 五、假设与决策

### 假设

1. 设计稿仅用于视觉参考，不直接替换 Vue 组件代码
2. 深色模式在设计稿中展示（浅色/深色 toggle 切换）
3. 图表组件（ECharts/图谱可视化）不在设计稿范围内
4. 国际化文案不在设计稿范围内
5. Element Plus 版本保持不变

### 关键设计决策

| 决策点 | 决策 | 理由 |
|--------|------|------|
| 表格 border | 保留 | 后台系统有 border 更利于数据阅读 |
| 卡片阴影 | shadow="never" + border | 扁平化更现代，深色模式一致性好 |
| 侧边栏宽度 | 240px（从 220px 调整） | 中文长菜单项需要更多空间 |
| 页面 padding | 统一 24px | 兼顾密度和舒适度 |
| 品牌渐变 | #6366F1 -> #8B5CF6 | 与主色同色系，替代不一致的旧渐变 |
| 布局框架 | CSS Grid 优先 | 比 el-row/el-col 更灵活 |

---

## 六、验证步骤

### 设计稿自检（每页）
1. 颜色一致性：所有颜色使用 CSS 变量，不存在旧色值硬编码
2. 间距一致性：页面 padding 24px、卡片间距 16px
3. 字体使用：标题用 var(--font-body)，代码用 var(--font-mono)
4. 深色模式：切换 dark class 后颜色正确适配
5. 布局正确：遵循对应的布局模式规范

### 覆盖范围验证
- 16 个设计稿覆盖全部 60+ 页面的改造指导
- 模式 B 列表页可指导：ScanVersionList, ReviewList, ReviewHistory, FactList, EvidenceSearch, TestCaseList, TestRunList, RiskList, ChangeTaskList, AgentHistory, LogList, DictionaryList, PromptList, PluginManagement, CodeRepoList, DatabaseList, DocumentList 等
- 模式 C 侧边栏页可指导：ProjectDetail 下全部 30+ 子页面
- 图谱视图可指导：CodeGraph, BusinessGraph, FeatureGraph, DataLineageGraph, RuntimeGraph, GraphDiff 等

### 颜色迁移验证
- 对照映射表，确认 51 个文件的所有旧色值都有明确替换目标
- 深色模式下替换后颜色对比度符合 WCAG AA
