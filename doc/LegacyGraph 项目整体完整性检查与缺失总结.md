# LegacyGraph 项目整体完整性检查与缺失总结

> **检查日期：** 2026-06-27  
> **检查范围：** 后端代码、前端代码、测试框架、数据库脚本、部署配置、项目文档  
> **检查人：** Claude Code 自动化全量检查  

---

## 目录

- [一、项目概况](#一项目概况)
- [二、已完成工作统计](#二已完成工作统计)
- [三、当前存在的缺失](#三当前存在的缺失)
  - [P0 阻断级缺口](#p0-阻断级缺口)
  - [P1 核心功能缺口](#p1-核心功能缺口)
  - [P2 质量与测试缺口](#p2-质量与测试缺口)
  - [P3 文档与部署缺口](#p3-文档与部署缺口)
- [四、修复建议顺序](#四修复建议顺序)
- [五、当前可交付性评估](#五当前可交付性评估)
- [六、总结](#六总结)

---

## 一、项目概况

**LegacyGraph** 是一个企业级系统分析与知识图谱平台，旨在通过大语言模型（LLM）、图数据库和向量检索技术，帮助开发团队理解、管理和现代化改造复杂的遗留系统。

**技术栈：**
- 后端：Spring Boot 3.5.0 + Java 21 + MyBatis-Plus + PostgreSQL + pgvector + Neo4j
- 前端：Vue 3 + TypeScript 5 + Vite 5 + Element Plus + Pinia + Vue Router
- 可视化：@vue-flow/core + @antv/G6 + ECharts
- AI：Spring AI 兼容 OpenAI 接口

**项目结构：**
```
LegacyGraph/
├── backend/                          # Spring Boot 后端
│   └── src/main/java/io/github/legacygraph/
│       ├── agent/                    # 6 个 LLM Agent
│       ├── config/                   # 配置类
│       ├── controller/               # 14 个 REST Controller
│       ├── dto/                      # 数据传输对象
│       ├── entity/                   # 22+ 个数据库实体
│       ├── extractors/               # 8 个代码抽取器
│       ├── repository/               # MyBatis-Plus Repository
│       ├── service/                  # 业务服务层
│       ├── task/                     # 后台异步任务
│       └── test/                     # 测试执行器
├── frontend/                         # Vue 3 前端
│   └── src/
│       ├── api/                      # API 接口（7 个模块）
│       ├── components/               # 可复用组件（分类）
│       ├── composables/              # 组合式函数
│       ├── directives/               # 自定义指令
│       ├── locales/                  # 国际化（中/英）
│       ├── router/                   # 路由配置
│       ├── stores/                   # Pinia 状态管理
│       ├── types/                    # TypeScript 类型定义
│       ├── utils/                    # 工具函数
│       └── views/                    # 页面组件（25+ 页面）
├── deploy/                           # Docker 部署配置
├── docs/                             # 数据库初始化脚本
└── doc/                              # 技术文档（16+ 份）
```

---

## 二、已完成工作统计

### 后端代码

| 模块 | 数量 | 状态 |
|------|------|------|
| Controller 层 | 14 个 | ✅ 全部完成 |
| Service 层 | 16+ 个 | ✅ 框架完成，部分逻辑需完善 |
| Entity 实体 | 28 个 | ✅ 全部定义完成 |
| Repository 数据访问 | 28 个 | ✅ 全部生成 |
| Agent AI 代理 | 6 个 | ✅ 框架完成 |
| Extractors 抽取器 | 8 个 | ✅ 全部实现 |
| 单元测试 | 20 个 | ✅ 覆盖所有 Controller |

### 前端代码

| 模块 | 数量 | 状态 |
|------|------|------|
| 页面路由 | 30+ 路由 / 25+ 页面 | ✅ 全部完成 |
| 可复用组件 | 30+ 组件 | ✅ 全部实现 |
| API 接口定义 | 7 个模块 | ✅ 全部定义 |
| 状态管理 (Pinia) | 5 个 Store | ✅ 全部完成 |
| 国际化 | 中/英双语 | ✅ 全部完成 |
| 单元测试 | 10 个 | ✅ 框架完成 |
| E2E 测试 | 11 个 | ✅ 核心流程示例完成 |

### 文档

| 文档类型 | 数量 | 位置 |
|----------|------|------|
| 架构设计 | 1 份 | [doc/架构设计文档.md](./架构设计文档.md) |
| 数据库设计 | 1 份 | [doc/数据库设计文档.md](./数据库设计文档.md) |
| 部署文档 | 1 份 | [doc/LegacyGraph 部署文档.md](./LegacyGraph%20部署文档.md) |
| 运维手册 | 1 份 | [doc/运维手册.md](./运维手册.md) |
| 开发规范 | 1 份 | [doc/开发规范文档.md](./开发规范文档.md) |
| LLM 接入设计 | 3 份 | 蓝图/改造方案/详细设计 |
| 前端页面设计 | 1 份 | [doc/LegacyGraph 前端页面详细设计文档.md](./LegacyGraph%20前端页面详细设计文档.md) |
| 三类图谱方法论 | 3 份 | 方法论/实现/落地计划 |
| 功能状态追踪 | 2 份 | 未完成清单/完整性报告/缺口审计 |
| **总计** | **16 份** | ✅ 文档体系完整 |

### 部署配置

| 配置项 | 文件 | 状态 |
|--------|------|------|
| 后端 Dockerfile | [backend/Dockerfile](../../backend/Dockerfile) | ✅ 多阶段构建完成 |
| 前端 Dockerfile | [frontend/Dockerfile](../../frontend/Dockerfile) | ✅ 多阶段构建完成 |
| 前端 Nginx 配置 | [frontend/nginx.conf](../../frontend/nginx.conf) | ✅ 配置完成 |
| Docker Compose | [deploy/docker-compose.yml](../../deploy/docker-compose.yml) | ✅ 全栈配置完成 |

**包含服务：** PostgreSQL 18 + pgvector、Neo4j 5.26、Redis 7、MinIO、backend、frontend

---

## 三、当前存在的缺失

### P0 阻断级缺口

| 编号 | 问题 | 影响 | 当前状态 |
|------|------|------|----------|
| **P0-1** | **数据库脚本 `init.sql` 字段与实体不一致** | 即使启动成功，CRUD 也会因列不存在失败 | 已识别，待修复 |

**具体差异：**

| 表名 | SQL 脚本缺失字段 | 实体已有字段 |
|------|------------------|--------------|
| `lg_code_repo` | `repoName`, `repoType`, `gitUrl`, `authType`, `username`, `includePattern`, `excludePattern`, `lastPullStatus`, `lastPullTime`, `lastScanTime`, `createdBy` | ✅ 实体存在 |
| `lg_db_connection` | `schemaName`, `password`, `readonly`, `includeTables`, `excludeTables`, `tableCount`, `lastScanTime`, `createdBy` | ✅ 实体存在 |
| `lg_document` | `versionId`, `fileType`, `filePath`, `fileSize`, `parseStatus`, `factCount`, `errorMessage`, `uploadedBy`, `uploadedAt`, `parsedAt` | ✅ 实体存在 |
| `lg_review_record` | `targetName`, `graphType`, `confidence`, `evidenceCount`, `priority`, `assignee`, `reviewedBy`, `reviewedAt` | ✅ 实体存在 |
| `lg_test_case` | `deleted` (逻辑删除) | ✅ 实体存在 `@TableLogic` |

> **影响分析：** 创建代码仓库、数据库连接、上传文档、提交审核都会失败。必须修复才能正常运行。

| 编号 | 问题 | 影响 | 当前状态 |
|------|------|------|----------|
| **P0-2** | **前端 TypeScript 类型检查未通过确认** | `npm run type-check` 可能存在错误，需要修复后才能确认可交付 | 已拆分出独立命令，待验证 |
| **P0-3** | **前端单元测试配置错误** | Vitest 误收 `tests/e2e/*` 文件，导致 `npm run test` 直接失败 | 配置问题，容易修复 |

---

### P1 核心功能缺口

| 编号 | 问题 | 位置 | 影响 |
|------|------|------|------|
| **P1-1** | **前端扫描未接入主扫描流程** | `ProjectScanner.java:94` | README 和文档宣称支持抽取 Vue 路由和前端 API，但实际主流程未接入，无法生成完整的功能图谱 |
| **P1-2** | **测试管理存在 mock 和随机结果** | `TestCaseController` | - 空列表时生成 mock 测试用例<br>- 生成用例数量随机<br>- 单例执行结果随机<br>- 测试运行列表/详情/日志 TODO<br>测试结果无法置信，不能作为真实依据 |
| **P1-3** | **审核列表生成演示 mock 数据** | `ReviewController:65-137` | 空列表时显示随机审核项，误导用户；审核人硬编码为 `admin`，无法追溯 |
| **P1-4** | **获取当前用户未使用 JWT 身份** | `AuthController:151` | `/api/auth/me` 固定返回 `admin`，多用户场景无法正确识别当前用户，权限和审计链路不闭合 |
| **P1-5** | **事实关联节点接口返回空** | `FactController:83` | 事实到图谱节点的追溯链路不完整，证据-审核-图谱之间联动缺失关键数据 |
| **P1-6** | **导出报告 PDF/Excel 未实现** | `ReportingService:510` | `exportReport` 仍是 TODO，只有 JSON 元数据，无法导出可读报告 |
| **P1-7** | **前后端数据源参数不匹配** | `CodeRepo` 前端 vs 后端 | 前端 `repoUrl` 后端 `gitUrl`/`repoName`/`repoType` 字段不匹配，创建代码仓库会失败 |

---

### P2 质量与测试缺口

| 编号 | 问题 | 影响 |
|------|------|------|
| **P2-1** | **前端测试环境不完整** | `setup.ts` 仅 stub 了 teleport，没有全局注册或 stub Element Plus 组件，单元测试运行会失败于 `el-tag`、`v-loading` 等无法解析 |
| **P2-2** | **后端 Spring AI 依赖被注释** | `pom.xml:196-214` Spring AI  starters 被注释掉了，LLM 功能实际上无法运行。需要修复兼容问题 |
| **P2-3** | **存在两套用户表 (`sys_user` + `lg_user`)** | 登录认证用 `sys_user`，但业务层还有一个 `lg_user`，边界不清晰，容易导致混淆 |
| **P2-4** | **Service 层单元测试覆盖率不足** | 只有 6 个 Service 单元测试，大部分 Service 没有测试 |

---

### P3 文档与部署缺口

| 编号 | 问题 | 影响 |
|------|------|------|
| **P3-1** | **旧文档结论与当前状态冲突** | `项目完整性检查报告.md` 和 `未完成功能清单.md` 仍声称 "100% 完成"、"可上线运行"，与实际缺口冲突，会误导判断 |
| **P3-2** | **部署文档缺少构建验证说明** | 没有记录当前 "后端可编译打包、前端可构建" 的实际状态，也没有给出验证命令和预期结果 |
| **P3-3** | **缺失 actuator 健康检查** | `pom.xml` 未引入 `spring-boot-starter-actuator`，Docker 部署无法做健康检查 |
| **P3-4** | **缺少 CHANGELOG / 更新历史** | 项目迭代过程没有记录，不便于追踪变更 |
| **P3-5** | **缺少 CONTRIBUTING 贡献指南** | 计划开源但未准备贡献指南 |

---

## 四、修复建议顺序

### 第一阶段：修复 P0 阻断问题（让项目能跑起来）

1. **修正数据库脚本** —— 以当前实体为准，更新 `docs/sql/init.sql`，确保字段一一匹配
   - `lg_code_repo` 补充缺失的 10+ 个字段
   - `lg_db_connection` 补充缺失的 7 个字段
   - `lg_document` 补充缺失的 10+ 个字段
   - `lg_review_record` 补充缺失的 7 个字段
   - `lg_test_case` 添加 `deleted` 字段
   - 更新 `lg_code_repo`、`lg_db_connection`、`lg_document` 的列名

2. **修复测试配置**
   - `vitest.config.ts` 修正排除规则：`exclude: [...configDefaults.exclude, 'tests/e2e/**/*']`
   - 完善 `tests/setup.ts`，stub Element Plus 组件和全局 API
   - 配置测试环境变量 `VITE_API_BASE_URL`

3. **验证前端类型检查**
   - 运行 `npm run type-check`
   - 修复所有 TypeScript 错误
   - 确认能完整通过

4. **恢复 Spring AI 依赖**
   - 解决 Spring Boot 3.5.0 与 Spring AI 2.0.0-M4 的兼容问题
   - 或者使用稳定版本的 Spring AI
   - 取消注释 pom.xml 中的 Spring AI 依赖

### 第二阶段：闭合 P1 核心业务缺口

1. **接入前端扫描**
   - 在 `ProjectScanner.startFullScan()` 中添加 `FrontendApiExtractor` 和 `VueRouteExtractor` 调用
   - 用 `FrontendGraphBuilder` 构建前端节点

2. **去掉测试管理的 mock 和随机**
   - 空列表返回空列表，不生成演示数据
   - 测试生成接真实的 LLM 生成，去掉随机数量
   - 测试执行结果持久化，不随机返回成功/失败
   - 补全测试运行列表、详情、日志的查询实现

3. **修复审核列表**
   - 空列表返回空列表，不生成 mock
   - 审核人从 JWT 安全上下文获取当前用户，不硬编码

4. **修复当前用户接口**
   - 从 JWT token 中解析出用户 ID
   - 查询对应用户，不固定返回 admin

5. **补全事实关联节点**
   - 实现 `FactController.getRelatedNodes()` 的真实查询逻辑
   - 联调事实-证据-图谱节点的追溯链路

6. **统一数据源参数**
   - 对齐前端 `CodeRepo` 类型与后端 DTO 字段名
   - 确保创建接口能正确绑定参数

### 第三阶段：P2 质量改进

1. **梳理用户表边界**
   - 决定保留哪一套用户体系
   - 删除冗余的表或调整代码使用统一的表

2. **补充 Service 层单元测试**
   - 优先补全核心服务：`ProjectScanner`, `GraphMergeService`, `ReportingService`, `VectorRetrievalService`

3. **添加 actuator 健康检查**
   - 在 `pom.xml` 添加 `spring-boot-starter-actuator`
   - 在 `docker-compose.yml` 配置健康检查端点

### 第四阶段：文档更新

1. **修正文档结论口径**
   - 更新 `项目完整性检查报告.md`，准确描述当前状态
   - 更新 `未完成功能清单.md`，反映真实的待办

2. **添加 CHANGELOG.md**
   - 记录项目重大变更和版本历史

3. **添加 CONTRIBUTING.md**
   - 描述开发环境搭建、代码提交流程、代码规范

4 **更新部署文档**
   - 添加构建验证命令和预期结果
   - 说明当前已知限制

---

## 五、当前可交付性评估

| 检查项 | 评估结果 |
|--------|----------|
| 后端本地编译 (`mvn -DskipTests compile`) | ✅ 通过 |
| 后端打包 (`mvn -DskipTests package`) | ✅ 通过 |
| 后端单元测试 (`mvn test`) | ⚠️ 未完整验证，需要测试数据库配置 |
| 前端生产构建 (`npm run build`) | ✅ 通过 |
| 前端类型检查 (`npm run type-check`) | ⚠️ 未验证通过 |
| 前端单元测试 (`npm run test`) | ❌ 配置错误导致失败 |
| 前端 E2E 测试 (`npm run test:e2e`) | ⚠️ 框架有，但未独立运行验证 |
| Docker Compose 配置解析 | ✅ 通过 |
| Docker 一键部署 | ⚠️ 未实际拉起验证 |
| 数据库初始化脚本 | ❌ 字段与实体不一致，无法正常工作 |
| 登录认证链路 | ⚠️ JWT 生成正常，但 `/me` 固定返回 admin |
| 全量代码扫描 | ⚠️ Java/MyBatis/数据库可用，前端扫描未接入 |
| LLM AI 分析 | ❌ Spring AI 依赖被注释，无法运行 |
| 报告导出 | ⚠️ JSON 元数据可用，PDF/Excel 未实现 |
| 文档可信度 | ⚠️ 旧结论需要更新 |

**当前整体状态：**

> ✅ **骨架完整** —— 前后端目录结构、Controller、Service、实体、页面、路由都已经建好
>
> ⚠️ **基础门槛已恢复** —— 后端可编译打包，前端可生产构建，Docker Compose 配置可解析
>
> ❌ **生产闭环未完成** —— 数据库字段不匹配、核心链路有 mock/random/TODO 占位、测试无法正常运行
>
> 需要按上述四阶段修复后，才能达到"功能完整、可上线运行"的状态。

---

## 六、总结

### 项目完成度估算

| 维度 | 完成度 | 说明 |
|------|--------|------|
| 代码骨架和框架 | 95% | 所有包结构、类、接口、页面都已创建 |
| 数据库实体定义 | 100% | 28 个实体完整定义 |
| REST API 接口 | 90% | 所有端点已定义，少数实现不完整 |
| 前端页面 | 100% | 所有规划页面都已实现 |
| 前端组件 | 95% | 高阶组件都完成，只有少量细节可以优化 |
| 核心业务逻辑 | ~70% | 主要框架完成，部分环节仍为占位实现 |
| 测试覆盖 | ~40% | Controller 测试完整，Service 和业务逻辑测试不足 |
| 数据库脚本 | ~60% | 表结构框架存在，但字段与代码不同步 |
| 文档 | 框架完整结论需要更新 | 16 份文档齐全，但部分结论过时 |
| 部署配置 | 95% | Dockerfile 和 Compose 完整，缺少健康检查 |

**项目整体完成度：约 75-80%**

### 关键建议

1. **优先修复数据库脚本** —— 这是最阻断的问题，不修复什么都跑不起来
2. **然后修复测试配置** —— 让测试能运行，才能保证修改不 regress
3**再闭合核心业务链路** —— 去掉 mock，让扫描、测试、审核、报告真正工作
4. **最后更新文档** —— 让文档反映真实状态，避免误导

---

**报告生成：** Claude Code  
**项目：** LegacyGraph  
**最后更新：** 2026-06-27
