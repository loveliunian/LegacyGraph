# LegacyGraph 老项目 AI 图谱理解平台

> 基于文档《三类图谱的具体实现.md》建设的可落地老项目 AI 图谱理解平台。

## 📋 项目简介

LegacyGraph 是一个专为老项目迁移、理解、资产盘点而设计的知识图谱平台。它将老项目的后端代码、前端代码、数据库结构、文档等统一解析抽取，构建**统一项目知识图谱**，并对外输出三类核心图谱：

1. **业务图谱**：说明系统有哪些业务域、业务流程、业务对象、业务规则、角色、状态流转
2. **功能图谱**：说明系统有哪些模块、菜单、页面、按钮、接口、权限、操作用例
3. **代码图谱**：说明接口、Controller、Service、Mapper、SQL、表、字段之间如何关联

更重要的是，平台能够根据图谱**自动生成测试用例、接口测试、E2E测试和数据库断言**，通过测试执行结果**反向验证图谱正确性**。

## 🏗️ 系统架构

```
老项目输入
    ↓
Project Scanner 项目扫描器
    ↓
Code / Frontend / DB / Doc / Config Extractor 抽取器
    ↓
Fact Store 事实库
    ↓
Graph Builder 图谱构建器
    ↓
PostgreSQL (元数据) + Neo4j (知识图谱) + pgvector (向量检索)
    ↓
三类图谱视图: 业务图谱 / 功能图谱 / 代码图谱
    ↓
Test Generator 测试生成器
    ↓
API测试 / E2E测试 / 数据库断言 / 链路断言
    ↓
Graph Validator 图谱验证器 ← 反向更新图谱置信度
    ↓
迁移报告 / 验证报告
```

## 🛠️ 技术栈

| 层级 | 技术 |
|---|---|
| 后端语言 | Java 21 |
| 框架 | Spring Boot 4.0.x |
| ORM | MyBatis-Plus |
| 关系数据库 | PostgreSQL 18 + pgvector |
| 图数据库 | Neo4j 5.x |
| 缓存 | Redis |
| 对象存储 | MinIO |
| Java代码解析 | JavaParser |
| SQL解析 | JSqlParser |
| 文档解析 | Apache Tika + Apache POI + PDFBox |
| API测试 | REST Assured |
| E2E测试 | Playwright |

## 🚀 快速开始

### 1. 启动依赖服务

使用 Docker Compose 一键启动所有依赖：

```bash
cd deploy
docker-compose up -d
```

启动后服务地址：

| 服务 | 地址 | 用户名/密码 |
|---|---|---|
| PostgreSQL | `localhost:5432` | `legacy_graph` / `legacy_graph` |
| Neo4j Browser | http://localhost:7474 | `neo4j` / `password` |
| MinIO Console | http://localhost:9001 | `minio` / `minio123456` |
| Redis | `localhost:6379` | - |

### 2. 初始化数据库

连接 PostgreSQL 执行初始化脚本：

```bash
psql -h localhost -p 5432 -U legacy_graph -d legacy_graph < docs/sql/init.sql
```

### 3. 初始化数据库

连接 PostgreSQL 执行初始化脚本：

```bash
psql -h localhost -p 5432 -U legacy_graph -d legacy_graph < docs/sql/init.sql
```

### 4. 启动后端服务

```bash
cd backend
mvn spring-boot:run
```

后端 API 地址：http://localhost:8080/api

Swagger 文档：http://localhost:8080/api/swagger-ui.html

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端地址：http://localhost:3000

## 📖 使用流程

### 项目接入流程

1. **创建项目** - 通过 API 创建项目
2. **配置扫描信息** - 填写代码位置、数据库连接、文档位置
3. **创建扫描版本** - 创建扫描版本
4. **启动扫描** - 系统自动执行扫描任务：
   - 后端代码扫描（Controller、Service、Mapper、SQL）
   - 前端代码扫描（路由、页面、API调用）
   - 数据库扫描（表、字段、约束）
   - 文档扫描（文本抽取、切片、向量化）
5. **查看进度** - 查询扫描进度
6. **人工确认** - 审核低置信度节点和关系
7. **生成测试用例** - 自动生成 API 测试和数据库断言
8. **执行测试** - 在测试环境执行测试
9. **查看报告** - 查看验证报告和图谱置信度

### 最小可运行版本验收场景

输入：一个 Spring Boot + MyBatis + PostgreSQL + Vue 的老项目

操作：
```
1. 创建项目
2. 上传后端代码
3. 上传前端代码
4. 上传数据库 DDL
5. 启动扫描
6. 查看代码图谱
7. 查看功能图谱
8. 生成接口测试用例
9. 执行测试
10. 查看验证报告
```

输出：
```
1. 接口清单
2. 表结构清单
3. 页面清单
4. 接口到表的完整调用链
5. 页面到接口的调用链
6. 初版功能图谱
7. API 测试用例
8. DB 断言
9. 测试执行结果
10. 图谱置信度报告
```

## 📁 项目结构

```
LegacyGraph/
├── backend/                     # 后端服务
│   ├── src/main/java/io/github/legacygraph/
│   │   ├── LegacyGraphApplication.java    # 启动类
│   │   ├── agent/              # AI Agent
│   │   │   └── DocUnderstandingAgent.java  # 文档业务事实抽取
│   │   ├── builder/             # 图谱构建器
│   │   │   ├── GraphBuilder.java         # 代码图谱构建
│   │   │   ├── FrontendGraphBuilder.java  # 前端图谱构建
│   │   │   └── BusinessGraphBuilder.java  # 业务图谱构建
│   │   ├── common/               # 通用枚举、结果封装
│   │   ├── config/               # Spring配置
│   │   ├── controller/           # REST接口
│   │   │   ├── ProjectController
│   │   │   ├── ScanController
│   │   │   ├── GraphQueryController
│   │   │   ├── ReviewController
│   │   │   ├── TestCaseController
│   │   │   └── ValidationController
│   │   ├── dto/                 # 请求响应DTO
│   │   ├── entity/              # 数据库实体 (14个表)
│   │   ├── extractors/          # 各类抽取器
│   │   │   ├── JavaControllerExtractor.java
│   │   │   ├── ServiceCallExtractor.java
│   │   │   ├── MyBatisXmlExtractor.java
│   │   │   ├── SqlTableExtractor.java
│   │   │   ├── VueRouteExtractor.java
│   │   │   ├── FrontendApiExtractor.java
│   │   │   ├── DatabaseMetadataExtractor.java
│   │   │   └── DocumentExtractor.java
│   │   ├── model/              # 抽取结果模型
│   │   ├── repository/          # MyBatis-Plus Repository
│   │   ├── service/             # 业务服务
│   │   │   ├── ProjectService
│   │   │   ├── GraphQueryService
│   │   │   ├── Neo4jSyncService
│   │   │   ├── TestCaseService
│   │   │   └── GraphValidatorService  # 置信度更新
│   │   ├── task/
│   │   │   └── ProjectScanner.java  # 项目扫描协调
│   │   └── test/                # 测试执行
│   │       ├── ApiTestExecutor.java    # REST Assured API 测试
│   │       └── E2eTestExecutor.java    # Playwright E2E 测试
│   ├── src/main/resources/
│   │   └── application.yml      # 配置文件
│   └── pom.xml
├── frontend/                    # 前端 Vue3 项目
│   ├── src/
│   │   ├── api/                 # API 封装
│   │   ├── views/
│   │   │   ├── project/         # 项目列表、详情
│   │   │   ├── graph/           # 代码/功能/业务图谱
│   │   │   ├── scan/            # 扫描任务
│   │   │   ├── review/          # 人工确认
│   │   │   └── test/            # 测试用例
│   │   └── router/             # 路由
│   ├── index.html
│   ├── package.json
│   └── vite.config.ts
├── docs/                        # 文档
│   ├── sql/
│   │   └── init.sql             # 数据库初始化脚本 (完全符合文档设计的14张表)
│   └── *.md                     # 设计文档
├── deploy/                      # 部署配置
│   └── docker-compose.yml       # Docker Compose 一键启动所有依赖
└── README.md
```

## 🎯 核心功能

### 代码抽取

- ✅ Java Controller 接口抽取（识别@RequestMapping等注解）
- ✅ Service 调用关系抽取（识别注入和方法调用）
- ✅ MyBatis XML 解析（识别 namespace、statement、SQL）
- ✅ SQL 表读写关系抽取（基于 JSqlParser）
- ✅ 数据库元数据抽取（从 information_schema 获取表和字段）
- ✅ 字段语义自动识别（主键、外键、状态字段、字典字段、逻辑删除、审计字段）

### 前端抽取

- ✅ Vue 路由抽取（解析路由配置，提取路径、标题、权限）
- ✅ 前端 API 调用抽取（识别 axios/request/fetch）
- ✅ 按钮和权限抽取（识别模板中的按钮和权限指令）
- ✅ 前后端 API 匹配打分（基于路径和方法匹配）

### 文档抽取

- ✅ Word/PDF/Markdown 文本抽取
- ✅ 文档按标题切片，保持token在合理范围
- ✅ AI 业务事实抽取 Prompt 模板（抽取业务域、流程、对象、规则）
- ✅ 功能映射（文档功能映射到已有的页面和接口）

### 图谱构建

- ✅ 统一节点类型定义（25+ 节点类型）
- ✅ 统一关系类型定义（20+ 关系类型）
- ✅ 节点去重规则
- ✅ API 路径归一化
- ✅ 前后端 API 匹配打分
- ✅ 置信度管理
- ✅ 证据溯源
- ✅ 同步到 Neo4j

### 业务图谱

- ✅ 业务域、业务流程、业务对象、业务规则节点构建
- ✅ 业务流程 -> 功能 -> 页面 -> 接口映射建立
- ✅ 基于名称相似度的自动功能映射

### 测试生成与验证

- ✅ API 测试用例自动生成
- ✅ 数据库断言自动生成
- ✅ 基于图谱链路生成测试步骤
- ✅ REST Assured API 测试执行
- ✅ Playwright E2E 测试代码生成
- ✅ 图谱验证器（根据测试结果反向更新置信度）
- ✅ 人工确认支持（确认/驳回/更新置信度）
- ✅ 验证报告统计

## 🔍 三类图谱查询

### 代码图谱常用查询

```cypher
// 查询某个接口完整调用链
MATCH p = (api:ApiEndpoint {nodeKey: $apiKey})-[:HANDLED_BY|CALLS|EXECUTES|READS|WRITES*1..8]->(n)
RETURN p;

// 查询某张表被哪些接口写入
MATCH p = (api:ApiEndpoint)-[:HANDLED_BY|CALLS|EXECUTES|WRITES*1..8]->(t:Table {nodeName: $tableName})
RETURN api, p;

// 查询某个功能对应页面、接口、表
MATCH p = (f:Feature {nodeKey: $featureKey})-[:EXPOSED_BY|CALLS|HANDLED_BY|EXECUTES|READS|WRITES*1..10]->(n)
RETURN p;
```

## 📊 验收指标 (MVP 目标)

| 指标 | 目标 |
|---|---|
| Controller 接口识别率 | >= 95% |
| MyBatis SQL 识别率 | >= 90% |
| SQL 表读写识别率 | >= 85% |
| 数据库表字段识别率 | >= 98% |
| 核心功能链路完整率 | >= 80% |
| P0/P1 接口测试覆盖率 | >= 70% |
| 图谱节点可追溯证据比例 | 100% |

## 📝 开发顺序

完全按照文档建议的开发顺序开发完成：

1. ✓ **后端接口扫描** - JavaParser 抽取 Controller
2. ✓ **MyBatis XML SQL 扫描** - 解析 namespace 和 SQL 语句
3. ✓ **数据库表结构扫描** - 抽取 information_schema 元数据
4. ✓ **接口 -> Controller -> Service -> Mapper -> SQL -> 表 调用链构建** - 完整链路
5. ✓ **Neo4j 代码图谱展示** - 同步到 Neo4j 支持图查询
6. ✓ **前端路由和 API 调用扫描** - 抽取 Vue 路由和 API 调用
7. ✓ **页面 -> 接口 -> 表 功能链路** - 建立完整功能链路
8. ✓ **文档解析和业务事实抽取** - Word/PDF 解析切片，AI 抽取
9. ✓ **AI 生成业务图谱** - 构建业务域、流程、对象、规则
10. ✓ **人工确认低置信度节点** - API 支持确认/驳回
11. ✓ **生成接口测试用例** - 基于图谱自动生成
12. ✓ **生成数据库断言** - 根据读写关系生成
13. ✓ **执行测试并回写图谱** - REST Assured 执行，结果回写更新置信度
14. ✓ **生成验证报告** - 输出置信度统计

## 🛡️ 安全设计

- 上传文件存储到 MinIO 私有桶
- 自动识别敏感信息并脱敏
- 默认禁止在生产环境执行测试
- 测试执行需要环境白名单
- 不把敏感配置传给 AI 模型

## 📄 许可证

MIT License
