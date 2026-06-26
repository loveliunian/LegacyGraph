# 前端测试说明

## 测试框架

- **Vitest**: 单元测试和组件测试
- **@vue/test-utils**: Vue 组件测试工具
- **jsdom**: DOM 模拟环境

## 测试类型

### 1. 单元测试 (Unit Tests)

- **Stores 测试**: 测试 Pinia 状态管理
  - `tests/unit/stores/user.store.test.ts`
  - `tests/unit/stores/project.store.test.ts`
  - `tests/unit/stores/graph.store.test.ts`
  - `tests/unit/stores/task.store.test.ts`

- **组件测试**: 测试单个组件
  - `tests/unit/components/ConfidenceBadge.test.ts`

- **API 测试**: 测试 API 调用逻辑
  - `tests/unit/api/api.test.ts`

### 2. 视图测试 (View Tests)

测试各个页面视图的渲染和基本功能：

- `tests/unit/views/LoginView.test.ts`
- `tests/unit/views/ProjectList.test.ts`
- `tests/unit/views/GraphView.test.ts`

### 3. 集成测试 (Integration Tests)

- `tests/unit/integration/router.test.ts`

## 运行测试

### 运行所有测试
```bash
npm run test
```

### 监听模式运行测试
```bash
npm run test:watch
```

### 运行测试并生成覆盖率报告
```bash
npm run test:coverage
```

### 使用 UI 界面运行测试
```bash
npm run test:ui
```

## 覆盖率说明

测试覆盖率报告包含以下指标：
- **% Stmts**: 语句覆盖率
- **% Branch**: 分支覆盖率
- **% Funcs**: 函数覆盖率
- **% Lines**: 行覆盖率

## 测试约定

1. **文件命名**: `*.test.ts` 或 `*.spec.ts`
2. **目录结构**: 与 `src/` 目录对应
3. **测试描述**: 使用 `describe('模块名称', () => { ... })`
4. **测试用例**: 使用 `it('功能描述', () => { ... })`

## 模拟 (Mock)

- **localStorage**: 在 `tests/setup.ts` 中统一模拟
- **axios**: 使用 `vi.mock('axios')` 模拟 API 调用
- **Element Plus**: 在 `tests/setup.ts` 中模拟组件和方法
- **Vue Flow**: 模拟图谱渲染组件
- **echarts**: 模拟图表库
