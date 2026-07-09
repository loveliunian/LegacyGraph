# LegacyGraph 前端优化 - 实施计划

## [x] Task 1: 修复 vite.config.ts 中 monaco-editor 错误引用
- **Priority**: high
- **Depends On**: None
- **Description**: 
  - 移除 vite.config.ts 中 manualChunks 对 monaco-editor 的引用，因为该依赖未在 package.json 中安装
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `programmatic` TR-1.1: 执行 `npm run build` 成功完成
  - `programmatic` TR-1.2: 构建输出中无 monaco-editor 相关警告或错误
- **Notes**: monaco-editor 当前未被项目使用，移除引用是安全的

## [x] Task 2: 修复 App.vue 事件监听器内存泄漏
- **Priority**: high
- **Depends On**: None
- **Description**: 
  - 在 App.vue 中添加 onUnmounted 钩子，清理 'locale-changed' 事件监听器
- **Acceptance Criteria Addressed**: AC-2
- **Test Requirements**:
  - `human-judgment` TR-2.1: 组件卸载时事件监听器正确清理
  - `programmatic` TR-2.2: 单元测试验证事件监听器注册/清理逻辑
- **Notes**: Vue 3 中 window 事件监听器不会自动清理，必须手动移除

## [x] Task 3: 修复路由守卫中 loadAllDicts() 异步时序问题
- **Priority**: high
- **Depends On**: None
- **Description**: 
  - 在 router beforeEach 中对 loadAllDicts() 添加 await，确保字典数据加载完成后再渲染页面
- **Acceptance Criteria Addressed**: AC-3
- **Test Requirements**:
  - `human-judgment` TR-3.1: 刷新页面后字典标签能正确显示
  - `programmatic` TR-3.2: 路由守卫测试验证异步加载顺序
- **Notes**: 当前代码调用 loadAllDicts() 但未 await，导致页面渲染时字典数据可能尚未就绪

## [x] Task 4: 路由标题国际化改造
- **Priority**: medium
- **Depends On**: None
- **Description**: 
  - 将 router/index.ts 中所有硬编码中文标题改为国际化 key
  - 在 locales/zh-CN.ts 和 locales/en-US.ts 中添加对应的翻译
- **Acceptance Criteria Addressed**: AC-4
- **Test Requirements**:
  - `human-judgment` TR-4.1: 切换语言后所有页面标题正确显示对应语言
  - `programmatic` TR-4.2: 路由 meta title 中无硬编码中文
- **Notes**: 需修改 20+ 个路由标题，涉及 locales 两个文件

## [x] Task 5: Dashboard 硬编码颜色提取为 CSS 变量
- **Priority**: medium
- **Depends On**: None
- **Description**: 
  - 将 Index.vue 中所有硬编码颜色（如 #409eff, #67c23a 等）替换为 Element Plus CSS 变量
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `human-judgment` TR-5.1: 暗色主题下 Dashboard 所有颜色正常显示
  - `programmatic` TR-5.2: 搜索 Dashboard 文件确认无硬编码颜色值
- **Notes**: Dashboard 当前 937 行，存在多处硬编码颜色，需逐一替换

## [x] Task 6: 统一表格组件使用模式
- **Priority**: medium
- **Depends On**: None
- **Description**: 
  - 修复 BaseTable 组件的分页逻辑，明确区分客户端分页和服务端分页模式
  - 为 ProjectList.vue 等使用 raw el-table 的视图提供迁移指引
- **Acceptance Criteria Addressed**: FR-4
- **Test Requirements**:
  - `human-judgment` TR-6.1: BaseTable 的分页行为与预期一致
  - `human-judgment` TR-6.2: ProjectList 分页功能正常工作
- **Notes**: 39 个视图文件使用 raw el-table，一次性全部改造工作量大，本任务重点修复 BaseTable 本身的问题

## [x] Task 7: 提升图谱组件复用率
- **Priority**: low
- **Depends On**: None
- **Description**: 
  - 检查 CodeGraph.vue 等视图，推广使用 GraphViewerOptimized 组件
- **Acceptance Criteria Addressed**: FR-7
- **Test Requirements**:
  - `human-judgment` TR-7.1: CodeGraph 使用统一的图谱查看器组件
  - `human-judgment` TR-7.2: 图谱渲染效果保持一致
- **Notes**: 属于代码质量优化，不影响核心功能