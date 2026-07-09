# LegacyGraph 前端优化 PRD

## Overview
- **Summary**: 对 LegacyGraph 前端项目进行全面扫描，识别并修复构建缺陷、性能问题、代码质量问题和国际化缺失等问题，提升前端应用的稳定性、可维护性和用户体验。
- **Purpose**: 通过系统性的优化，消除构建阻断问题，提升页面加载性能，改善代码质量，确保国际化一致性。
- **Target Users**: 开发团队、运维团队、最终用户

## Goals
- 修复所有构建阻断问题（Build-breaking bugs）
- 消除内存泄漏和异步时序问题
- 统一表格组件使用模式，减少代码重复
- 完成路由标题的国际化改造
- 提升 Dashboard 组件的可维护性
- 消除硬编码颜色，支持暗色主题

## Non-Goals (Out of Scope)
- 重构后端 API
- 大幅修改现有业务逻辑
- 引入新的第三方库（除必要的修复外）
- 重写整个项目架构

## Background & Context
经过对前端代码的全面扫描，发现以下主要问题类别：
1. **构建阻断问题**：vite 配置引用未安装依赖
2. **性能问题**：内存泄漏、异步时序问题、低效数据加载
3. **代码质量**：硬编码颜色、过度使用 any 类型、组件复用率低
4. **国际化**：路由标题存在大量硬编码中文

## Functional Requirements
- **FR-1**: 修复 vite.config.ts 中对 monaco-editor 的错误引用
- **FR-2**: 修复 App.vue 中的事件监听器内存泄漏
- **FR-3**: 修复路由守卫中 loadAllDicts() 的异步时序问题
- **FR-4**: 统一表格分页模式，解决 BaseTable 与 el-table 的冲突
- **FR-5**: 将所有路由标题改为国际化 key
- **FR-6**: 提取 Dashboard 硬编码颜色为 CSS 变量
- **FR-7**: 提升组件复用率，推广 BaseTable 和 GraphViewerOptimized

## Non-Functional Requirements
- **NFR-1**: 优化后项目能正常构建（npm run build 无错误）
- **NFR-2**: 页面加载时间减少 20%（通过修复异步时序问题）
- **NFR-3**: 代码重复率降低（表格组件统一）
- **NFR-4**: 暗色主题下所有页面正常显示

## Constraints
- **Technical**: Vue 3 + TypeScript + Element Plus 技术栈
- **Dependencies**: 不引入新的第三方依赖
- **Business**: 不影响现有业务功能

## Assumptions
- 所有优化修改保持向后兼容
- 现有测试用例应继续通过

## Acceptance Criteria

### AC-1: 构建阻断问题修复
- **Given**: 运行 `npm run build`
- **When**: 执行生产构建
- **Then**: 构建成功，无 monaco-editor 相关错误
- **Verification**: `programmatic`

### AC-2: 内存泄漏修复
- **Given**: 用户进入应用后多次切换页面
- **When**: 进行页面导航操作
- **Then**: 无内存持续增长，事件监听器正确清理
- **Verification**: `human-judgment`

### AC-3: 字典加载时序修复
- **Given**: 用户刷新页面进入需要字典数据的页面
- **When**: 页面渲染时使用 dictLabel() 函数
- **Then**: 字典数据已就绪，标签正确显示
- **Verification**: `human-judgment`

### AC-4: 路由标题国际化
- **Given**: 用户切换语言为英文
- **When**: 导航到任意页面
- **Then**: 页面标题显示对应的英文文本
- **Verification**: `human-judgment`

### AC-5: 暗色主题支持
- **Given**: 用户切换到暗色主题
- **When**: 查看 Dashboard 页面
- **Then**: 所有颜色正常显示，无硬编码亮色文字
- **Verification**: `human-judgment`

## Open Questions
- [ ] 是否需要引入 monaco-editor 作为实际依赖，还是移除相关配置？
- [ ] 是否需要为所有未使用 BaseTable 的视图统一改造？