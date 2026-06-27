# LegacyGraph - 代码知识图谱平台

## 项目简介

LegacyGraph 是一个企业级代码分析与知识图谱平台，旨在通过图数据库和向量检索技术，帮助开发团队理解、管理和迁移复杂的遗留系统。

## 核心功能

### 1. 图谱可视化
- 统一知识图谱展示
- 节点类型：业务域、功能模块、API 接口、控制器、服务、数据访问层、数据库表、字段
- 支持力导向、环形、网格、分层等多种布局算法
- 节点和关系的详细属性展示
- 路径分析和影响分析

### 2. 智能分析
- 代码 AST 解析与分析
- 业务流程自动识别
- API 调用链追踪
- 数据流向分析
- 代码异味检测
- 技术债务评估

### 3. 向量检索
- 基于内容的代码相似度搜索
- 语义化查询支持
- 向量索引加速检索
- 支持模糊搜索和精确匹配

### 4. 报告与统计
- 代码质量报告
- 迁移就绪度评估
- 测试覆盖率报告
- 置信度趋势分析
- 图谱质量评估

### 5. 迁移辅助
- 风险识别与评估
- 影响范围分析
- 重构建议生成
- 迁移计划生成

## 技术架构

### 前端技术栈
- **框架**: Vue 3 + TypeScript
- **构建工具**: Vite 5
- **UI 组件库**: Element Plus
- **图谱渲染**: @vue-flow/core + @antv/g6
- **状态管理**: Pinia
- **路由**: Vue Router
- **国际化**: Vue I18n
- **测试**: Vitest + Playwright

### 后端技术栈
- **框架**: Spring Boot
- **数据库**: PostgreSQL + pgvector (向量存储)
- **图数据库**: Neo4j (可选)
- **认证**: JWT + Spring Security
- **文档生成**: Swagger/OpenAPI

## 快速开始

### 环境要求
- Node.js >= 18.0.0
- JDK >= 17
- PostgreSQL >= 15 (with pgvector extension)
- Docker (推荐)

### 前端开发

```bash
# 进入前端目录
cd frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev

# 构建生产版本
npm run build

# 运行单元测试
npm run test

# 运行 E2E 测试
npx playwright test

# 代码检查
npm run lint

# 代码格式化
npm run format
```

### 后端开发

```bash
# 进入后端目录
cd backend

# 使用 Maven 构建
mvn clean install

# 启动应用
mvn spring-boot:run

# 运行测试
mvn test
```

### Docker 部署

```bash
# 完整启动所有服务
docker-compose up -d

# 仅启动数据库
docker-compose up -d postgres

# 查看日志
docker-compose logs -f
```

## 项目结构

### 前端目录结构

```
frontend/
├── src/
│   ├── api/                    # API 接口定义
│   ├── assets/                 # 静态资源
│   ├── components/             # 公共组件
│   │   ├── common/             # 通用组件 (表格、表单、骨架屏等)
│   │   ├── charts/             # 图表组件
│   │   ├── code/               # 代码相关组件
│   │   ├── upload/             # 上传组件
│   │   └── graph/              # 图谱组件
│   ├── composables/            # 组合式函数
│   ├── directives/             # 自定义指令
│   ├── locales/                # 国际化
│   ├── router/                 # 路由配置
│   ├── stores/                 # Pinia 状态管理
│   ├── styles/                 # 全局样式
│   ├── types/                  # TypeScript 类型定义
│   ├── utils/                  # 工具函数
│   ├── views/                  # 页面组件
│   ├── App.vue                 # 根组件
│   └── main.ts                 # 应用入口
├── tests/                      # 测试目录
│   ├── unit/                   # 单元测试
│   ├── e2e/                    # E2E 测试
│   └── setup.ts                # 测试配置
├── public/                     # 公共资源
├── vite.config.ts              # Vite 配置
├── vitest.config.ts            # Vitest 配置
├── playwright.config.ts        # Playwright 配置
└── package.json
```

### 后端目录结构

```
backend/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── io/github/legacygraph/
│   │   │       ├── config/        # 配置类
│   │   │       ├── controller/    # REST API 控制器
│   │   │       ├── entity/        # 数据库实体
│   │   │       ├── dto/           # 数据传输对象
│   │   │       ├── repository/    # 数据访问层
│   │   │       ├── service/       # 业务逻辑层
│   │   │       ├── agent/         # AI Agent
│   │   │       ├── aspect/        # AOP 切面
│   │   │       └── util/          # 工具类
│   │   └── resources/             # 配置文件
│   └── test/                      # 测试代码
└── pom.xml
```

## 核心组件文档

### 图谱组件

#### GraphViewer.vue
图谱查看器组件，提供完整的图可视化与交互功能。

**Props**:
```typescript
{
  nodes: Node[]           // 节点数据
  edges: Edge[]           // 边数据
  height?: string         // 容器高度 (默认 '600px')
  editable?: boolean      // 是否可编辑 (默认 true)
}
```

**Events**:
- `@node-click(node)` - 节点点击事件
- `@edge-click(edge)` - 边点击事件
- `@node-drag(node)` - 节点拖拽结束事件
- `@connect(connection)` - 创建连接事件

**功能**:
- MiniMap 小地图导航
- 缩放/平移控制
- 多种布局切换
- 图谱导出（JSON、图片）
- 自定义节点样式

#### GraphAnalysisPanel.vue
图谱分析面板，提供高级图分析功能。

**功能**:
- 路径分析：最短路径、所有路径查找
- 影响分析：上游/下游影响范围
- 邻居展开：按层级展开节点关联
- 聚合视图：按类型/置信度分组展示

### 代码组件

#### CodeDiffViewer.vue
代码差异对比组件，支持分栏视图和统一视图。

**功能**:
- 新增/删除行高亮
- 变更统计信息
- 复制代码功能
- 视图模式切换
- 行号显示

#### CodePreview.vue
代码预览组件，支持语法高亮。

**功能**:
- 多语言语法高亮
- 行号显示
- 行内搜索
- 跳转到指定行
- 全屏查看
- 复制/下载功能

### 文件上传

#### DragUpload.vue
拖拽上传组件，支持大文件分片上传。

**功能**:
- 拖拽上传
- 文件类型/大小校验
- 分片上传
- 上传进度显示
- 断点续传
- 取消上传

### 通知中心

#### NotificationCenter.vue
通知中心组件，管理系统通知。

**功能**:
- 通知分类
- 未读数量标记
- 批量已读
- 实时推送支持

## 配置说明

### 环境变量

在 `frontend/.env` 文件中配置：

```env
VITE_APP_TITLE=LegacyGraph
VITE_API_BASE_URL=http://localhost:8080/api
VITE_PWA_ENABLED=true
```

### 主题配置

在 `src/styles/variables.css` 中自定义主题颜色：

```css
:root {
  --el-color-primary: #409eff;
  --el-color-success: #67c23a;
  --el-color-warning: #e6a23c;
  --el-color-danger: #f56c6c;
  --el-color-info: #909399;
}
```

### 国际化

添加新语言支持：

1. 在 `src/locales/` 下创建语言文件
2. 在 `src/locales/index.ts` 中注册
3. 更新 `LangSwitcher.vue` 中的语言列表

## API 文档

启动后端后访问 `http://localhost:8080/swagger-ui.html` 查看完整 API 文档。

### 主要 API

- `/api/auth/*` - 认证相关
- `/api/projects/*` - 项目管理
- `/api/graph/*` - 图谱操作
- `/api/scan/*` - 扫描管理
- `/api/reports/*` - 报告生成
- `/api/vector/*` - 向量检索

## 开发指南

### 新增组件

1. 在 `src/components/` 对应目录创建组件
2. 遵循 Composition API + `<script setup>` 规范
3. 添加完整的 TypeScript 类型定义
4. 编写单元测试

### 新增页面

1. 在 `src/views/` 下创建页面组件
2. 在 `src/router/index.ts` 中添加路由配置
3. 实现页面功能

### 代码规范

- 遵循 ESLint 配置规则
- 使用 Prettier 格式化代码
- 组件命名使用 PascalCase
- 组合式函数使用 use 前缀命名
- 常量使用 UPPER_SNAKE_CASE

## 测试指南

### 单元测试

```bash
# 运行所有测试
npm run test

# 监听模式
npm run test:watch

# 查看覆盖率
npm run test:coverage
```

### E2E 测试

```bash
# 运行所有浏览器测试
npx playwright test

# 仅运行 Chromium
npx playwright test --project=chromium

# 显示测试过程
npx playwright test --headed

# 生成测试报告
npx playwright show-report
```

## 性能优化

### 首屏加载优化
- 路由懒加载：`component: () => import('./views/...')`
- 代码分割：按功能模块动态导入
- 骨架屏：页面加载前显示骨架屏
- 资源预加载：关键资源预加载

### 图谱性能
- 聚合节点：大数据量时按类型聚合
- 按需渲染：视口内节点优先渲染
- Web Worker：复杂计算移至后台线程
- 虚拟滚动：节点列表使用虚拟滚动

### 其他优化
- 图片懒加载：`v-lazy-load` 指令
- 防抖/节流：搜索输入等高频操作
- 状态持久化：Pinia 插件实现
- 内存泄漏检测：组件卸载时清理资源

## 部署指南

### 生产环境构建

```bash
# 构建前端
cd frontend && npm run build

# 构建后端
cd backend && mvn clean package -Pprod
```

### Docker 部署

```bash
# 构建镜像
docker build -t legacy-graph:latest .

# 运行容器
docker run -d \
  --name legacy-graph \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  legacy-graph:latest
```

### Nginx 配置

```nginx
server {
    listen 80;
    server_name your-domain.com;

    location / {
        root /var/www/legacy-graph/dist;
        try_files $uri $uri/ /index.html;
    }

    location /api {
        proxy_pass http://localhost:8080/api;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

## 常见问题

### Q: 图谱渲染卡顿怎么办？
A: 启用节点聚合、减少显示节点数、使用 Web Worker 进行布局计算。

### Q: 如何自定义图谱节点样式？
A: 在 `GraphViewer.vue` 中自定义节点组件，或通过 CSS 变量修改主题颜色。

### Q: 大文件上传失败？
A: 检查后端配置的最大文件大小，前端在 `DragUpload.vue` 中也有相应配置。

### Q: 如何添加新的节点类型？
A: 在 `CustomNode.vue` 中添加新类型的图标和样式，后端同步更新节点类型枚举。


## 许可证

本项目采用 Apache 2.0 许可证 - 详见 [LICENSE](LICENSE) 文件

## 联系方式

- 项目主页：https://github.com/legacygraph/legacygraph
- 问题反馈：https://github.com/legacygraph/legacygraph/issues

