# 系统关系总览 — 业务 / 功能 / 代码 / 数据

> 资料扫描完成后，对 LegacyGraph 系统中**业务、功能、代码、数据四层之间关系**的结论性总结。
> 用途：①后续 QA 文档的事实底座；②用户可直接查看的系统理解结论。
> 生成日期：2026-07-06 ｜ 口径：以当前代码为准（V1–V37，43 张活跃表）

---

## 这组文档是什么

LegacyGraph 对外呈现**三类图谱**（业务/功能/代码），但它们是同一统一图谱的投影视图。从"关系"视角，更稳定的骨架是**四层**：业务层（为什么）、功能层（怎么触发）、代码层（由什么实现）、数据层（落到什么表）。

本组文档把四层关系**结论化**，回答"是什么"，而非"怎么做/怎么设计/怎么优化"。

## 与既有文档的边界

| 既有文档 | 性质 | 与本组关系 |
|---|---|---|
| `doc/整体技术文档/`（架构/数据库/部署/运维/开发规范） | 设计性 | 本组聚焦"四层关系"，不重复架构/部署细节 |
| `doc/三类图谱的方法论.md` | 通用方法论 | 本组 `01` 提炼其关系框架并落到 LegacyGraph 自身 |
| `doc/三类图谱的具体实现.md` / `获取实现说明.md` | 实现性 | 本组引用其节点/关系类型，做关系结论 |
| `doc/项目升级计划/资料扫描到三类图谱构建流程与AI优化研究.md` | 流程/研究 | 本组 `02` 引用其扫描流程做链路证据 |
| `doc/项目升级计划/QA问答优化方案.md` | 方案性 | 本组 `03` 作为其事实底座 |
| `graphify-out/GRAPH_REPORT.md` | 扫描报告 | 本组 `02`/`03` 引用其社区/缺口作证据 |

> 区别一句话：既有文档讲"怎么做、怎么设计、怎么优化"；本组讲"四层关系是什么"。

## 文档清单与阅读路径

| 文档 | 内容 | 适合谁 |
|---|---|---|
| [`01-关系总览与映射框架.md`](01-关系总览与映射框架.md) | 四层定义、核心映射链路、层间边类型表、追溯路径、证据与置信度规则、缺口冲突 | 想理解关系模型的所有人 |
| [`02-分层映射详解.md`](02-分层映射详解.md) | LegacyGraph 自身 12 业务域的 业务↔功能↔代码↔数据 映射 + 5 条贯穿链路 + 扫描证据 + 已知缺口 | 开发者 / 架构理解 / 影响分析 |
| [`03-QA事实底座.md`](03-QA事实底座.md) | 人读 FAQ 卡片 + 机器可用结构化关系表 + 内置 QA 对接说明 | QA 建设 / 新人上手 / Agent 检索 |

**推荐阅读顺序**：
- 想快速理解系统 → `01` §1–§3 → `03` Part A（FAQ）
- 想做影响分析 / 改动评估 → `01` §5 → `02` §13
- 想建 QA / 写 FAQ → `03` 全文 → `01` §6–§8
- 想核查某业务域的代码/数据 → `02` 对应域小节

## 如何用于 QA（两条路）

**路 1：人读 FAQ**（`03` Part A）
17 条高频问答，按"系统总览 / 业务域 / 缺口边界"三类组织，每条带证据来源。可直接作为后续 FAQ 文档的素材。

**路 2：机器结构化**（`03` Part B + C）
- Part B1 业务能力映射总表 → 向量化入 `lg_vector_document` 供 `HybridRetrievalService` 召回。
- Part B 每行作为 `KnowledgeClaim` 写入 `lg_knowledge_claim`，供 `GraphRagPlanExecutor` 按 `subjectKeys` 检索（替代"按版本 Top 50"）。
- Part A FAQ 的 question embedding 入 `lg_semantic_cache`，相似问题命中。
- 回答时展开为 `GraphRagEvidenceCard`（字段对齐 `01` §7）。

**回答底线**：有证据才回答；`CONFIRMED` 直接答，`PENDING_CONFIRM` 标注，缺口标"⚠ 暂无法高置信回答"；来源矛盾时列全不覆盖。

## 扫描数据来源与口径

| 来源 | 内容 | 日期 |
|---|---|---|
| graphify 扫描 | LegacyGraph 仓库自身，8573 节点 / 22199 边 / 399 社区 | 2026-07-06 |
| CodeGraph MCP | 调用关系图（`.codegraph/`） | 持续索引 |
| 代码核查 | `NodeType.java`(35) / `EdgeType.java`(31) / 34 Controller / Flyway V1–V37 | 当前 |
| 既有文档 | README / 架构 / 数据库 / 三类图谱系列 / QA 方案 | 复核 |

**口径优先级**（冲突时以此为准）：当前代码 > Flyway 迁移 > 数据库设计文档 > README > 扫描报告 > 既有方案文档。

> 已知文档偏移已校正：研究文档提 V20/V21，实际已 V37；`lg_graph_node/edge` 已废弃；插件/配额无专属表。

## 术语速查

| 术语 | 含义 | 详见 |
|---|---|---|
| 四层 | 业务 / 功能 / 代码 / 数据 | `01` §1–§2 |
| 三类图谱 | 业务 / 功能 / 代码图谱（四层的投影视图） | `01` §9 |
| 核心链路 | `BusinessDomain → Feature → Api → Method → Mapper → Sql → Table → Column` | `01` §3 |
| EvidenceCard | 证据卡片，QA 引用事实的最小单元 | `01` §7 |
| Claim | 知识断言，AI 来源默认 PENDING_CONFIRM | `01` §6.4 |
| 三类差异 | planned_or_stale / static_only / dynamic_only | `01` §6.3 |
| 置信度回写 | PASS +0.10 / FAIL −0.20 / 人工 +0.15 | `01` §6.2 |
| 废弃表 | lg_graph_node / lg_graph_edge（图谱存 Neo4j，不存 PG） | `02` §0.2 |

## 项目规模速查

| 类别 | 数量 |
|---|---:|
| 业务域 | 12 |
| 后端 Controller | 34 |
| 后端包 | 28 |
| 数据库活跃表 | 43（V1–V37） |
| 前端 views 目录 | 21 |
| NodeType / EdgeType | 35 / 31 |
| graphify 节点/边/社区 | 8573 / 22199 / 399 |

## 系统功能化进度（2026-07-06）

文档组描述的四层关系能力已从"文档结论"落地为系统功能（M1–M4 实施，详见 [`04-落地实施计划.md`](04-落地实施计划.md) §12）：

| 里程碑 | 状态 | 入口 |
|---|---|---|
| M1 QA 事实底座接入 | ✅ | `POST /lg/system-overview/ingest-builtins` |
| M2 系统自分析 | ✅ | `GET /lg/projects/{projectId}/system-overview`；前端"系统关系总览"工作台 |
| M3 Claim 编译主链路 + 双轨 | ✅ | `legacygraph.graph.write-mode` 配置 |
| M4 自身导入 + 插件增强 | ✅ | `POST /lg/self-analysis/bootstrap`；`POST /lg/plugins/register` |

- 后端 23 测试通过 + 前端 type-check 通过 + 全量 test-compile 通过
- 后续未做：ToolRouter 深度接入 CodeGraph MCP、AdapterExecutionService 双轨自动切换、`lg_system_overview_snapshot` 快照表

## 维护

- 更新触发：Controller/Service/表结构变更、新增 Flyway 迁移、graphify 重新扫描。
- 核查命令：`ls backend/src/main/java/io/github/legacygraph/controller/`；`grep "create table" backend/src/main/resources/db/migration/*.sql`；CodeGraph MCP `codegraph_files`。
- 不变锚点：`NodeType.java` / `EdgeType.java` / `GraphRagEvidenceCard.java`，变更时同步全组。
