# LegacyGraph — 遗留系统 AI 知识图谱分析平台

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Vue](https://img.shields.io/badge/Vue-3-blue.svg)](https://v3.vuejs.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-379%20passed-green.svg)]()
[![TypeScript](https://img.shields.io/badge/type--check-passing-green.svg)]()

## 项目简介

**LegacyGraph** 是一个企业级遗留系统分析与知识图谱平台，通过大语言模型（LLM）、图数据库（Neo4j）、向量检索（pgvector）和 Redis 缓存技术，把代码库、数据库、文档、前端页面连接成一张可追溯、可验证、可审核的统一知识网络。

核心理念：**静态分析给事实，LLM 做归纳与补全，自动测试负责反证，人工审核兜底**。

## 应用场景

- 🏢 **遗留系统现代化**：扫描 Java Spring Boot + Vue 老项目，构建三类图谱，辅助迁移决策
- 👥 **新人快速上手**：自然语言问答「用户注册涉及哪些表？」「修改密码经过哪些模块？」
- 🔍 **架构审计**：识别代码异味、技术债务、N+1 查询、不合理依赖
- 📊 **影响分析**：基于图谱依赖链评估变更影响范围
- 🧪 **测试增强**：AI 自动生成 API/E2E/DB 测试用例，执行结果回写置信度

## 核心能力

### 三类图谱

| 图谱 | 内容 | 数据来源 |
|---|---|---|
| 代码图谱 | Controller/Service/Mapper/SQL/表/字段调用链 | Java AST + MyBatis XML + JDBC 元数据 |
| 功能图谱 | 页面/按钮/API/权限/菜单 → 后端接口映射 | Vue 组件 + axios 调用 + Controller 注解 |
| 业务图谱 | 业务域/流程/角色/对象/规则/状态流转 | 文档 AI 抽取 + 人工确认 |

所有节点和关系均带 `evidence`（文件、行号、SQL、文档片段），`confidence`（0–1），`status`（CONFIRMED/PENDING_CONFIRM/REJECTED）。

### LLM Agent 体系（15+ Agent）

| Agent | 功能 |
|---|---|
| CodeFactAgent | 代码语义事实抽取 |
| DocUnderstandingAgent | 文档流程/规则/角色/对象抽取 |
| FeatureMappingAgent | 页面/API/权限/功能映射 |
| GraphMergeAgent | 图谱节点去重合并裁决 |
| TestCaseAgent | API/E2E/DB 测试用例生成 |
| ReviewAgent | 审核建议与冲突分析 |
| QaAgent | 自然语言图谱问答（RAG + 图邻域） |
| SqlAdvisorAgent | SQL 性能优化建议 |
| TestFailureAnalysisAgent | 测试失败根因分析 |
| ReportInsightAgent | 报告洞察与行动建议 |
| RefactorAgent | 代码异味重构建议 |
| ChangeImpactAgent | 语义级变更影响分析 |
| MigrationAgent | 迁移代码自动转换 |
| PrDescriptionAgent | PR 描述/提交信息生成 |
| DbSchemaAnalysisAgent | 数据库 Schema 业务分析 |

全部 Agent 经 `LlmGateway` 统一调用——支持多模型动态路由、Prompt 模板渲染、PII 脱敏、结构化校验、`PromptRun` 审计（token/latency/inputHash）和 Redis 结果缓存（TTL 7d）。

### RAG 问答

五段式检索增强生成：规则过滤 → pgvector 向量召回（Top-30）→ Neo4j 图邻域扩展（1–2 跳）→ 重排序 → LLM 生成。回答带 `usedEvidence`（节点/边/文档片段三元组）、`relatedNodeKeys` 和 `confidence`。

### 扫描后 AI 编排

AI 能力不是孤立的——`ProjectScanner` 扫描完成后由 `AiScanOrchestrator` 自动执行四个 AI 子任务：
- 文档分片 → 业务事实抽取 → 业务图谱构建
- 前端 API ↔ 后端接口映射 → 待确认 CALLS 边
- 高价值 API 节点测试用例生成（上限 20 节点）
- 低置信节点审核任务生成（上限 50 个）

### 测试闭环

从功能节点出发，沿 `Feature → Page → API → Service → SQL → Table` 链路生成测试；API 用 REST Assured、E2E 用 Playwright、断言覆盖 HTTP/JSONPath/SQL/状态；结果回写图谱置信度（PASS +0.10，FAIL −0.20，人工审核通过 +0.15）。

### Redis 缓存体系

全链路容错缓存——Redis 不可用时静默降级回源。已落地 9 个场景：

| 场景 | TTL | 实现 |
|---|---|---|
| LLM 结果缓存 | 7d | `inputHash` 去重，命中跳过 LLM 调用 |
| JWT 登出黑名单 | token 剩余有效期 | 真登出 + 强制下线 |
| 扫描进度缓存 | 3s / 30min | 吸收前端高频轮询 |
| 图谱视图/报告缓存 | 按版本 | `GraphCacheInvalidator` 统一失效 |
| 配置/字典/Prompt/提供商缓存 | 1–6h | @Cacheable + 写时 @CacheEvict |
| 项目概览/验证报告/迁移报告 | 1min–1h | 多写点失效 |
| 向量检索/节点详情 | 60s–3min | 编程式缓存 + 写时 evict |

### 报告与度量

- 迁移就绪度评估（低置信节点、孤立节点、缺口分析）
- 五维图谱质量度量（覆盖率/证据完备度/待审核比例/测试通过率/运行时验证比例）
- AI 行动建议（按优先级排序、带证据来源的可执行清单）

## 技术架构

### 四段式架构

```
静态事实层 → 检索增强层 → Agent 编排层 → 验证回写层
```

**LLM 不放在最前面，而是放在事实库、向量检索与图谱构建之间。**

### 技术栈

| 层 | 选型 |
|---|---|
| 后端框架 | Spring Boot 3.5.0 + Java 21 |
| AI 集成 | Spring AI + LlmGateway（多模型路由） |
| 关系数据库 | PostgreSQL + jsonb + GIN 索引 |
| 向量检索 | pgvector（HNSW 索引） |
| 图数据库 | Neo4j 5.x（向量索引 + 全文组合检索） |
| 缓存 | Redis 7.x（Lettuce 客户端） |
| 对象存储 | MinIO |
| ORM | MyBatis-Plus 3.5.16 |
| 认证 | JWT + Redis 黑名单 |
| 前端框架 | Vue 3 + TypeScript + Vite 5 |
| UI 组件 | Element Plus |
| 图可视化 | @antv/G6 5.x |
| 状态管理 | Pinia |
| 国际化 | Vue I18n |

### 模型支持

所有兼容 OpenAI 接口的模型均可接入（通过配置 `endpoint` + `api-key`）。推荐：
- 私有部署：Qwen3（vLLM/TGI）
- 云弹性：GLM-4.5 / DeepSeek-V4
- Embedding：text-embedding-3-small（512–768 维）

## 项目规模

| 类别 | 数量 |
|---|---:|
| 后端 Controller | 17 |
| 后端 Entity | 32 |
| 数据库表 | 32（含 lg_runtime_trace / lg_prompt_run / lg_llm_provider 等） |
| Vue 页面 | 36 |
| 后端测试 | 379 全绿 |
| AI Agent | 15+ |
| Prompt 模板 | 13（全部纳入契约测试） |
| 缓存场景 | 9 个落地 |

## 快速开始

### 环境要求

- Node.js ≥ 18、JDK ≥ 21、Maven ≥ 3.8
- PostgreSQL ≥ 15（需 pgvector 扩展）
- Neo4j ≥ 5.x、Redis ≥ 7.x、MinIO
- Docker（可选，仅构建前后端镜像；外部依赖经 `.env` 注入）

### Docker 启动

```bash
git clone https://github.com/loveliunian/LegacyGraph.git
cd LegacyGraph/deploy
cp .env.example .env    # 填写外部 PostgreSQL/Neo4j/Redis/MinIO 连接信息
docker compose up -d --build
```

首次需手动在外部 PostgreSQL 执行 `docs/sql/init.sql` 初始化 32 张表。

### 本地开发

```bash
# 后端
cd backend
mvn clean test      # 379 tests
mvn spring-boot:run

# 前端
cd frontend
npm install
npm run dev
npm run type-check  # 类型门禁
```

## 项目结构

```
LegacyGraph/
├── backend/src/main/java/io/github/legacygraph/
│   ├── agent/           # AI Agent（15+）
│   ├── builder/         # 图谱构建器（GraphBuilder/BusinessGraphBuilder/FrontendGraphBuilder）
│   ├── config/          # 配置（RedisConfig/SecurityConfig/WebConfig）
│   ├── controller/      # REST API（17 个 Controller）
│   ├── dao/             # 数据访问（Neo4jGraphDao）
│   ├── dto/             # 数据传输对象
│   ├── entity/          # 数据库实体（32 个）
│   ├── extractors/      # 代码抽取器（Java/MyBatis/Vue/DB/Document）
│   ├── llm/             # LLM 网关 + Prompt 模板加载器
│   ├── repository/      # MyBatis-Plus Repository
│   ├── service/         # 业务逻辑（含 CacheService/GraphCacheInvalidator）
│   ├── task/            # 后台任务（ProjectScanner/AiScanOrchestrator）
│   ├── test/            # 测试执行器（ApiTestExecutor/DbAssertionExecutor/E2eTestExecutor）
│   └── util/            # 工具类
├── frontend/src/
│   ├── views/           # 36 个页面（dashboard/project/source/scan/graph/fact/review/test/migration/system/audit）
│   ├── components/      # 组件（graph/charts/common/upload）
│   ├── api/             # 前端 API 层
│   ├── stores/          # Pinia 状态管理
│   ├── locales/         # 中/英国际化
│   └── router/          # 路由配置
├── deploy/              # Docker Compose + Dockerfile
├── docs/sql/            # 数据库初始化脚本（init.sql）
└── doc/                 # 设计文档（全景设计文档.md）
```

## API 端点

| 端点 | 说明 |
|---|---|
| `/api/auth/*` | JWT 认证（登录/登出/刷新/当前用户） |
| `/api/projects/*` | 项目管理 + 报告洞察 |
| `/api/source/*` | 数据源（代码仓库/数据库连接/文档上传） |
| `/api/scan/*` | 扫描管理（全量/增量/进度/版本） |
| `/api/graph/*` | 图谱查询（统一视图/API链/表影响/功能视图/业务视图） |
| `/api/fact/*` | 事实管理 + 文档事实抽取 |
| `/api/review/*` | 审核管理（待审列表/确认/驳回/批量） |
| `/api/tests/*` | 测试用例管理 + 执行结果回写 |
| `/api/reports/*` | 报告生成（迁移就绪度/置信度/覆盖率/图谱质量/五维指标） |
| `/api/vector/*` | 向量检索（语义搜索/相似节点） |
| `/api/qa/*` | 自然语言问答 |
| `/api/agents/*` | AI Agent 端点（SQL分析/失败分析/报告洞察/重构/影响/迁移/PR描述） |
| `/api/llm/*` | LLM 提供商 + Prompt 模板管理 |
| `/api/system/*` | 系统配置 + 字典 |
| `/api/trace/*` | 运行时链路（Trace 上报/拓扑/列表） |
| `/api/audit/*` | 审计日志 |

## 设计文档

详细设计见 [`doc/LegacyGraph 平台全景设计文档.md`](doc/LegacyGraph%20平台全景设计文档.md)，涵盖：总体架构、LLM 网关与 Agent 体系、RAG 与向量检索、缓存策略、测试闭环、安全与运维、实施进度与后置项。

## 许可证

Apache 2.0 — 详见 [LICENSE](LICENSE)。
