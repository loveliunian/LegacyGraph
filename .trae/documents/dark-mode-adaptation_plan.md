# 全局深色模式适配计划

## 项目调研结论

### 当前状态分析

项目已具备基础的深色模式支持：

* ✅ `variables.css` 中已定义 `:root` 和 `html.dark` 的 CSS 变量

* ✅ `app.ts` store 中实现了主题切换逻辑（light/dark/auto）

* ✅ `main.ts` 中引入了 Element Plus 深色模式 CSS (`element-plus/theme-chalk/dark/css-vars.css`)

* ✅ 已有 `ThemeSwitcher.vue` 和 `ThemeSettings.vue` 组件

### 问题识别

部分组件使用了硬编码的浅色模式颜色，在深色模式下显示异常：

| 组件文件                       | 问题描述              | 严重程度 |
| -------------------------- | ----------------- | ---- |
| `LoginPage.vue`            | 背景渐变、文字颜色硬编码      | 高    |
| `CodeViewer.vue`           | 背景色、边框硬编码         | 高    |
| `JsonViewer.vue`           | 背景色、文字颜色硬编码       | 中    |
| `GraphViewerOptimized.vue` | 面板背景、加载遮罩硬编码      | 中    |
| `CustomNode.vue`           | 节点背景色硬编码          | 中    |
| `CodeDiffViewer.vue`       | 背景色、边框硬编码         | 中    |
| `BaseChart.vue`            | ECharts 图表未适配深色主题 | 低    |

## 实施步骤

### Phase 1: 基础样式优化

**目标**：完善全局 CSS 变量，确保 Element Plus 组件正确响应主题切换

**修改文件**：

1. **`frontend/src/styles/variables.css`**

   * 补充缺失的深色模式变量

   * 添加滚动条深色模式样式

   * 添加自定义组件的通用深色模式变量

### Phase 2: 核心组件适配

**目标**：修复主要布局和导航组件的深色模式显示

**修改文件**：

1. **`frontend/src/components/AppLayout.vue`**

   * 确保所有颜色使用 CSS 变量

2. **`frontend/src/views/login/LoginPage.vue`**

   * 将背景渐变改为使用 CSS 变量

   * 将文字颜色改为使用 CSS 变量

### Phase 3: 代码相关组件适配

**目标**：修复代码查看和对比组件的深色模式显示

**修改文件**：

1. **`frontend/src/components/common/CodeViewer.vue`**

   * 将背景色、边框改为使用 CSS 变量

   * 将文字颜色改为使用 CSS 变量

2. **`frontend/src/components/code/CodeDiffViewer.vue`**

   * 将背景色、边框改为使用 CSS 变量

   * 添加深色模式下的 diff 高亮样式

3. **`frontend/src/components/code/CodePreview.vue`**

   * 当前已使用深色主题（Atom One Dark），保持不变但需确保与系统主题协调

### Phase 4: 数据展示组件适配

**目标**：修复 JSON 查看器和图表组件的深色模式显示

**修改文件**：

1. **`frontend/src/components/common/JsonViewer.vue`**

   * 将背景色改为使用 CSS 变量

   * 调整 JSON 语法高亮颜色以适配深色模式

2. **`frontend/src/components/charts/BaseChart.vue`**

   * 添加深色模式支持，通过 props 传递主题

### Phase 5: 图谱组件适配

**目标**：修复图谱查看器和节点组件的深色模式显示

**修改文件**：

1. **`frontend/src/components/graph/GraphViewerOptimized.vue`**

   * 将面板背景、加载遮罩改为使用 CSS 变量

   * 将文字颜色改为使用 CSS 变量

2. **`frontend/src/components/graph/CustomNode.vue`**

   * 将节点背景色改为使用 CSS 变量

   * 调整边框和阴影样式

### Phase 6: 测试验证

**目标**：确保深色模式在所有场景下正常工作

**执行步骤**：

1. 运行单元测试：`npm test`
2. 运行 E2E 测试：`npm run test:e2e`
3. 手动验证主题切换功能

## 关键依赖与注意事项

### 依赖关系

* Element Plus 已内置深色模式支持，只需正确使用 CSS 变量

* highlight.js 使用 Atom One Dark 主题，适合深色模式

* ECharts 需要通过配置项适配主题

### 技术要点

1. **CSS 变量优先级**：使用 `var(--el-*)` 变量而非硬编码颜色值
2. **`:deep()`** **选择器**：用于覆盖 Element Plus 组件内部样式
3. **`html.dark`** **类**：通过该类名实现全局深色模式切换
4. **过渡动画**：添加 `transition` 属性确保主题切换平滑

### 风险评估

| 风险            | 影响          | 缓解措施               |
| ------------- | ----------- | ------------------ |
| 部分第三方库不支持深色模式 | 图表、代码高亮显示异常 | 使用 CSS 变量覆盖或条件加载主题 |
| 自定义组件样式冲突     | 某些区域显示异常    | 逐一检查并修复硬编码颜色       |
| 测试覆盖不足        | 回归问题        | 运行完整测试套件并手动验证      |

## 预期结果

完成后，系统将具备以下能力：

* ✅ 全局深色模式切换（点击主题切换按钮）

* ✅ 跟随系统主题设置（auto 模式）

* ✅ 所有组件正确响应主题变化

* ✅ 平滑的主题切换动画

* ✅ 测试覆盖深色模式功能

## 验收标准

1. **功能验证**：主题切换按钮能正常切换 light/dark/auto 模式
2. **视觉验证**：所有页面在深色模式下无明显视觉问题
3. **测试验证**：单元测试和 E2E 测试全部通过
4. **性能验证**：主题切换无明显卡顿

