# LegacyGraph - 系统 AI 知识图谱分析平台

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Vue 3](https://img.shields.io/badge/Vue-3-blue.svg)](https://v3.vuejs.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

## 项目简介

**LegacyGraph** 是一个企业级系统分析与知识图谱平台，旨在通过大语言模型（LLM）、图数据库和向量检索技术，帮助开发团队理解、管理和现代化改造复杂的遗留系统。

LegacyGraph 能够自动扫描你的代码库和数据库，构建包含业务域、模块、API、服务、表结构的完整知识图谱，并通过 AI 分析帮助团队更快理解系统，降低迁移风险。

## 🎯 应用场景

- 🏢 **企业遗留系统现代化**：帮助团队理解庞大的遗留系统，制定迁移计划
- 👥 **新人接手**：快速了解系统架构和业务逻辑，缩短上手时间
- 🔍 **架构审计**：识别代码异味、技术债务和不合理依赖
- 📊 **影响分析**：分析变更影响范围，降低修改风险
- 🧪 **测试辅助**：AI 辅助生成测试用例，提升覆盖率

## ✨ 核心功能

### 1. 自动扫描与知识抽取

- **多维度代码扫描**
  - Java Controller / Service 业务逻辑抽取
  - MyBatis XML SQL 解析
  - Vue 路由和前端 API 抽取
  - SQL 表结构自动提取
- **数据库元数据抽取**
  - 支持直接连接数据库抽取表结构
  - 表、字段、主键、外键关系识别
  - 基于列注释推断业务含义
- **文档抽取**
  - 支持 Word、PDF、Text 等文档解析
  - 业务需求文档向量化存储

### 2. 知识图谱可视化

- 统一知识图谱展示
- **节点类型**: 业务域、功能模块、API 接口、控制器、服务、数据访问层、数据库表、字段、前端页面
- 支持力导向、环形、网格、分层等多种布局算法
- 节点和关系的详细属性展示
- 路径分析和影响分析
- 支持图谱导出（JSON、PNG 图片）

### 3. AI 智能分析

- 🤖 **LLM 驱动分析**：基于大语言模型的代码理解和业务识别
- 业务流程自动识别
- API 调用链追踪
- 数据流向分析
- 代码异味检测
- 技术债务评估
- 多轮对话式代码审查

### 4. 向量检索

- 基于内容的代码相似度搜索
- 语义化查询支持
- 向量索引加速检索
- 支持模糊搜索和精确匹配
- 基于 pgvector 原生向量存储，无需额外向量数据库

### 5. 报告与统计

- 代码质量报告
- 迁移就绪度评估
- 测试覆盖率分析
- 置信度趋势分析
- 图谱质量评估

### 6. 迁移辅助

- 风险识别与评估
- 影响范围分析
- 重构建议生成
- 迁移计划生成

### 7. 测试自动化

- AI 辅助生成测试用例
- 支持 API 测试、数据库断言、E2E 测试
- 测试执行与结果追踪
- 覆盖率报告生成

## 🏗️ 技术架构

### 前端技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Vue | 3.x | 前端框架 |
| TypeScript | 5.x | 类型系统 |
| Vite | 5.x | 构建工具 |
| Element Plus | 2.x | UI 组件库 |
| @vue-flow/core | latest | 图谱流式布局 |
| @antv/G6 | 5.x | 图可视化引擎 |
| Pinia | latest | 状态管理 |
| Vue Router | 4.x | 路由 |
| Vue I18n | 9.x | 国际化 |

### 后端技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.5.0 | 应用框架 |
| Java | 21 | 开发语言 |
| Spring AI | 2.0.0-M4 | AI 集成 |
| PostgreSQL | 18+ | 关系数据库 + pgvector 向量扩展 |
| Neo4j | 5.x | 图数据库（可选） |
| Redis | 7.x | 缓存 |
| MinIO | latest | 对象存储 |
| MyBatis-Plus | 3.5.16 | ORM 框架 |
| JavaParser | 3.25.8 | Java AST 解析 |
| JWT | 0.12.3 | 认证 |

## 🚀 快速开始

### 环境要求

- Node.js >= 18.0.0
- JDK >= 21
- Maven >= 3.8
- PostgreSQL >= 15 (with [pgvector](https://github.com/pgvector/pgvector) extension)
- Docker & Docker Compose (推荐)

### 🐳 Docker 一键启动（推荐）

```bash
# 克隆项目
git clone https://github.com/legacygraph/LegacyGraph.git
cd LegacyGraph

# 启动所有服务（PostgreSQL, Neo4j, Redis, MinIO, backend, frontend）
cd deploy
docker-compose up -d

# 查看启动日志
docker-compose logs -f
```

启动完成后访问：
- 前端界面: http://localhost
- 后端 API: http://localhost:8080
- Swagger 文档: http://localhost:8080/swagger-ui.html
- Neo4j Browser: http://localhost:7474

### 🔧 本地开发

#### 前端开发

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

#### 后端开发

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

### ⚙️ 配置说明

#### 环境变量

**前端** (`frontend/.env.local`):

```env
VITE_APP_TITLE=LegacyGraph
VITE_API_BASE_URL=http://localhost:8080/api
```

**后端** (`backend/src/main/resources/application.yml`):

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/legacy_graph
    username: legacy_graph
    password: legacy_graph
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:your-api-key}
      base-url: https://api.openai.com
  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: password
```

> **重要**: 要使用 AI 分析功能，必须配置 OpenAI API Key（或兼容 OpenAI 的接口）。

## 📁 项目结构

```
LegacyGraph/
├── backend/                          # 后端服务
│   └── src/main/java/io/github/legacygraph/
│       ├── LegacyGraphApplication.java
│       ├── agent/                    # AI Agent 代理
│       │   ├── CodeFactAgent.java       # 代码事实抽取
│       │   ├── DocUnderstandingAgent.java # 文档理解
│       │   ├── FeatureMappingAgent.java  # 特征映射
│       │   ├── GraphMergeAgent.java      # 图谱合并
│       │   ├── ReviewAgent.java          # 代码审查
│       │   └── TestCaseAgent.java        # 测试用例生成
│       ├── config/                   # 配置类
│       ├── controller/               # REST API 控制器
│       ├── dto/                      # 数据传输对象
│       ├── entity/                   # 数据库实体
│       ├── extractors/               # 代码抽取器
│       │   ├── DatabaseMetadataExtractor.java
│       │   ├── DocumentExtractor.java
│       │   ├── FrontendApiExtractor.java
│       │   ├── JavaControllerExtractor.java
│       │   ├── MyBatisXmlExtractor.java
│       │   ├── ServiceCallExtractor.java
│       │   ├── SqlTableExtractor.java
│       │   └── VueRouteExtractor.java
│       ├── llm/                      # LLM 网关
│       ├── model/                    # 领域模型
│       ├── repository/               # 数据访问层
│       ├── service/                  # 业务逻辑层
│       ├── task/                     # 后台任务
│       ├── test/                     # 测试执行器
│       └── util/                     # 工具类
├── frontend/                         # 前端界面
│   └── src/
│       ├── api/                    # API 接口定义
│       ├── assets/                 # 静态资源
│       ├── components/             # 公共组件
│       │   ├── common/             # 通用组件
│       │   ├── charts/             # 图表组件
│       │   ├── code/               # 代码相关组件
│       │   ├── graph/              # 图谱组件
│       │   ├── upload/             # 上传组件
│       │   └── ...
│       ├── composables/            # 组合式函数
│       ├── directives/             # 自定义指令
│       ├── locales/                # 国际化（中文/英文）
│       ├── router/                 # 路由配置
│       ├── stores/                 # Pinia 状态管理
│       ├── types/                  # TypeScript 类型定义
│       ├── utils/                  # 工具函数
│       └── views/                  # 页面组件
│           ├── dashboard/          # 仪表板
│           ├── project/            # 项目管理
│           ├── scan/               # 扫描管理
│           ├── graph/              # 图谱浏览
│           ├── report/             # 分析报告
│           ├── migration/          # 迁移辅助
│           ├── test/               # 测试管理
│           ├── review/             # AI 审查
│           ├── audit/              # 审计日志
│           └── settings/           # 系统设置
├── deploy/                          # 部署配置
│   ├── docker-compose.yml          # Docker Compose 完整部署
│   ├── backend/Dockerfile          # 后端 Docker 镜像
│   └── frontend/Dockerfile         # 前端 Docker 镜像
├── docs/                            # 文档
│   └── sql/                        # 数据库初始化脚本
└── README.md
```

## 📖 使用指南

### 第一步：创建项目

1. 登录系统后，点击「新建项目」
2. 填写项目名称、描述
3. 选择扫描配置

### 第二步：配置数据源

支持两种扫描方式：
- **代码仓库扫描**：配置代码仓库地址，自动拉取代码进行分析
- **数据库连接**：配置数据库连接，自动抽取表结构和关系

### 第三步：启动扫描

1. 点击「开始扫描」
2. 系统会自动：
   - 解析代码 AST，抽取业务事实
   - 连接数据库，提取元数据
   - LLM 分析代码语义，识别业务域
   - 构建知识图谱
   - 生成向量嵌入
   - 保存到数据库

### 第四步：浏览分析

- 在「图谱视图」查看完整系统架构
- 点击节点查看详情和代码片段
- 使用「路径分析」查找两个模块之间的调用关系
- 使用「影响分析」查看修改影响范围

### 第五步：查看报告

- 查看代码质量报告
- 查看迁移就绪度评估
- 下载报告分享给团队成员

## 🧩 核心组件

### 前端组件

#### `GraphViewer.vue`
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
- `node-drag(node)` - 节点拖拽结束事件
- `connect(connection)` - 创建连接事件

**功能**:
- MiniMap 小地图导航
- 缩放/平移控制
- 多种布局切换
- 图谱导出（JSON、图片）
- 自定义节点样式

#### `GraphAnalysisPanel.vue`
图谱分析面板，提供高级图分析功能。

**功能**:
- 路径分析：最短路径、所有路径查找
- 影响分析：上游/下游影响范围
- 邻居展开：按层级展开节点关联
- 聚合视图：按类型/置信度分组展示

## 📚 API 文档

启动后端后访问 `http://localhost:8080/swagger-ui.html` 查看完整 API 文档。

### 主要端点

| 端点 | 说明 |
|------|------|
| `/api/auth/*` | 用户认证 |
| `/api/projects/*` | 项目管理 |
| `/api/graph/*` | 图谱查询 |
| `/api/scan/*` | 扫描管理 |
| `/api/source/*` | 数据源管理 |
| `/api/fact/*` | 事实管理 |
| `/api/reports/*` | 报告生成 |
| `/api/vector/*` | 向量检索 |
| `/api/llm/*` | LLM Agent |
| `/api/testcase/*` | 测试用例管理 |
| `/api/review/*` | AI 审查 |
| `/api/audit/*` | 审计日志 |

## 🧪 测试

### 前端测试

```bash
# 运行所有单元测试
npm run test

# 监听模式
npm run test:watch

# 查看覆盖率
npm run test:coverage
```

### 后端测试

```bash
# 运行所有测试
mvn test

# 运行单个测试类
mvn test -Dtest=ProjectControllerTest
```

## 🚀 部署

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
docker build -t legacygraph/backend:latest ./backend
docker build -t legacygraph/frontend:latest ./frontend

# 使用 docker-compose 启动
cd deploy
docker-compose up -d
```

### Nginx 配置示例

```nginx
server {
    listen 80;
    server_name your-domain.com;

    location / {
        root /var/www/legacygraph/dist;
        try_files $uri $uri/ /index.html;
    }

    location /api {
        proxy_pass http://localhost:8080/api;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

## ⚡ 性能优化

### 首屏加载优化
- 路由懒加载
- 代码分割：按功能模块动态导入
- 骨架屏：页面加载前显示骨架屏
- 资源预加载：关键资源预加载

### 图谱性能
- 聚合节点：大数据量时按类型聚合
- 按需渲染：视口内节点优先渲染
- Web Worker：复杂计算移至后台线程
- 虚拟滚动：节点列表使用虚拟滚动

### 后端性能
- Redis 缓存：频繁查询缓存
- 分页查询：大数据集分页返回
- 异步任务：长时间扫描异步执行
- 连接池：合理配置数据库连接池

## ❓ 常见问题

### Q: 需要 Neo4j 吗？可以只用 PostgreSQL 吗？
A: 当前架构 PostgreSQL 是必需的，用于存储业务数据和向量。Neo4j 是可选的，用于高级图查询。如果你只需要基本的图谱可视化，不使用 Neo4j 也可以运行。

### Q: 如何使用国产 LLM 如通义千问、文心一言？
A: 由于使用 Spring AI，只要接口兼容 OpenAI 格式，配置 `base-url` 和 `api-key` 即可使用。

### Q: 图谱渲染卡顿怎么办？
A: 启用节点聚合、减少显示节点数、使用 Web Worker 进行布局计算。

### Q: 支持哪些编程语言？
A: 当前主要支持 Java Spring Boot + Vue 项目。其他语言可以通过数据库扫描功能分析数据层。欢迎贡献更多语言的抽取器！

### Q: 大文件上传失败？
A: 检查 Spring Boot 配置的 `spring.servlet.multipart.max-file-size`，前端在 `DragUpload.vue` 中也有相应配置。

## 🤝 贡献

欢迎贡献代码！请参考 [CONTRIBUTING.md](docs/CONTRIBUTING.md)。

开发流程：
1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add some amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

## 📄 许可证

本项目采用 Apache 2.0 许可证 - 详见 [LICENSE](LICENSE) 文件。

## 👥 联系方式

- 项目主页：https://github.com/legacygraph/LegacyGraph
- 问题反馈：https://github.com/legacygraph/LegacyGraph/issues
- 作者：liuchengliang01

## 🙏 致谢

感谢以下开源项目：

- [Spring Boot](https://spring.io/projects/spring-boot)
- [Vue](https://vuejs.org/)
- [Element Plus](https://element-plus.org/)
- [AntV G6](https://g6.antv.vision/)
- [pgvector](https://github.com/pgvector/pgvector)
- [JavaParser](https://javaparser.org/)

---

如果你觉得这个项目对你有帮助，请给我们一个 ⭐ Star！
