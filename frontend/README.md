# LegacyGraph 前端项目

老项目 AI 图谱理解平台 - 前端应用

## 技术栈

- **框架**: Vue 3 (Composition API)
- **构建工具**: Vite
- **语言**: TypeScript
- **UI 组件库**: Element Plus
- **状态管理**: Pinia
- **路由**: Vue Router
- **图表库**: ECharts
- **日期处理**: Day.js

## 项目结构

```
frontend/
├── src/
│   ├── api/              # API 接口封装
│   │   └── index.ts
│   ├── components/       # 通用组件
│   │   ├── ConfidenceBadge.vue
│   │   ├── EvidencePanel.vue
│   │   └── NodeDetailDrawer.vue
│   ├── router/         # 路由配置
│   │   └── index.ts
│   ├── stores/         # Pinia 状态管理
│   │   ├── user.ts
│   │   ├── project.ts
│   │   ├── graph.ts
│   │   └── task.ts
│   ├── types/          # TypeScript 类型定义
│   │   └── index.ts
│   ├── views/          # 页面组件
│   │   ├── login/      # 登录页面
│   │   ├── project/    # 项目管理
│   │   ├── source/     # 资料接入
│   │   ├── scan/       # 扫描任务
│   │   ├── graph/      # 图谱中心
│   │   ├── fact/       # 事实与证据
│   │   ├── review/     # 人工审核
│   │   ├── test/       # 测试验证
│   │   └── report/     # 验证报告
│   ├── App.vue
│   └── main.ts
├── public/
├── package.json
├── tsconfig.json
├── vite.config.ts
└── .env.development
```

## 功能模块

### 1. 登录与权限

- 登录页面
- Token 管理
- 路由守卫

### 2. 项目管理

- 项目列表
- 创建/删除项目
- 项目概览
- 统计卡片展示

### 3. 资料接入

- 代码仓库配置 (Git)
- 数据库连接配置
- 文档上传与解析

### 4. 扫描任务

- 任务列表
- 任务进度
- 新建扫描向导
- 任务日志查看

### 5. 图谱中心

- 统一图谱
- 代码图谱
- 业务图谱
- 功能图谱
- 数据血缘图谱
- 运行链路图谱

### 6. 事实与证据

- 事实列表
- 证据检索
- 关联节点查看

### 7. 人工审核

- 审核队列
- 审核历史
- 节点详情查看
- 证据面板

### 8. 测试验证

- 测试用例管理
- AI 生成测试用例
- 测试执行记录

### 9. 验证报告

- 节点统计
- 风险分析
- 测试覆盖率
- AI 分析建议

## 快速开始

### 安装依赖

```bash
npm install
```

### 开发环境运行

```bash
npm run dev
```

### 生产环境构建

```bash
npm run build
```

### 代码检查

```bash
npm run lint
```

## 开发说明

### 环境变量

- `.env.development`: 开发环境
- `.env.production`: 生产环境

### 状态管理

使用 Pinia 进行状态管理，包括：
- `userStore`: 用户信息和权限
- `projectStore`: 项目上下文
- `graphStore`: 图谱相关状态
- `taskStore`: 任务进度管理

### API 调用

所有 API 调用统一在 `src/api/index.ts` 中定义，使用 axios 进行请求。
